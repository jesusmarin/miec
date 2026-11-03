package com.miec.orchestration;

import com.miec.action.*;
import com.miec.authorization.AuthorizationPersistenceService;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;
import com.miec.domain.EventType;
import com.miec.execution.ExecutableActionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;
import static com.miec.orchestration.PlanExecutionContracts.*;

/**
 * Transacciones breves de coordinación. Bloquea sistema → ciclo → plan → run → permisos;
 * nunca llama al destinatario ni invoca el coordinador IR-009 dentro de la transacción.
 */
@Service
class PlanExecutionStore {
    private final PlanActivityQuery planActivity;
    private final InterventionSupport store;
    private final InterventionPlans plans;
    private final AuthorizationPersistenceService permissions;
    private final ExecutionOutcomeQueryService outcomes;
    private final ExecutionActivityService activity;
    private final ExecutableActionCatalog catalog;
    private final ExecutionIncidentService incidents;

    /** Recibe servicios reales de autorización, reloj y proyección de IR-009; no duplica sus intentos. */
    PlanExecutionStore(InterventionSupport store,InterventionPlans plans,AuthorizationPersistenceService permissions,
            ExecutionOutcomeQueryService outcomes,ExecutionActivityService activity,ExecutableActionCatalog catalog,ExecutionIncidentService incidents,PlanActivityQuery planActivity) {
        this.planActivity=planActivity;
        this.store=store; this.plans=plans; this.permissions=permissions; this.outcomes=outcomes; this.activity=activity; this.catalog=catalog;this.incidents=incidents;
    }

    /** Credencial interna de una adquisición; nunca se acepta ni se devuelve como comando HTTP. */
    record Claim(UUID runId,UUID planId,String owner,long generation,String actor) { }

    /** Vista interna persistida utilizada para verificar versión, propiedad y huella. */
    record Row(UUID id,UUID plan,UUID cycle,String fingerprint,String state,long version,long generation,
               String owner,Instant lease,String createdBy,String updatedBy,Instant createdAt,Instant updatedAt) { }

    /** Datos internos del siguiente despacho; la definición siempre proviene del plan aprobado. */
    record Dispatch(InterventionViews.PlanAction action,UUID authorizationId) { }

    /** Reserva una única ejecución por plan y realiza E06 a E07 junto con el registro durable y sus pasos. */
    @Transactional(timeout=10)
    public Claim reserve(UUID cycleId,UUID planId,Start command,String actor) {
        store.lifecycle.validate(command);
        var locked=plans.lock(planId); var cycle=locked.cycle(); var plan=locked.plan();
        planActivity.requireOpen(planId);
        if(!cycle.cycleId().equals(cycleId)) throw fail(BAD_REQUEST,"PLAN_CYCLE_MISMATCH","El plan no pertenece al ciclo");
        if(!rows(planId,false).isEmpty()) throw fail(CONFLICT,"PLAN_EXECUTION_EXISTS","El plan ya tiene coordinación; consulte su ejecución");
        store.expect(cycle,command.expectedCycleVersion(),"E06_PREPARADO");
        if(plan.version()!=command.expectedPlanVersion()) throw fail(CONFLICT,"PLAN_VERSION_CONFLICT","La versión del plan cambió");
        validatePlan(plan);
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM execution_attempt WHERE plan_id=?",Integer.class,planId)>0
                ||store.jdbc.queryForObject("SELECT COUNT(*) FROM execution_operation WHERE plan_id=?",Integer.class,planId)>0)
            throw fail(CONFLICT,"IR009_HISTORY_EXISTS","El plan ya tiene historial IR-009 y requiere revisión");
        var authorizationIds=new ArrayList<UUID>();
        for(var action:plan.actions()) authorizationIds.add(authorize(plan,action.actionId()));
        UUID id=UUID.randomUUID(); var now=store.lifecycle.now();
        store.jdbc.update("""
                INSERT INTO plan_execution(run_id,plan_id,cycle_id,plan_fingerprint,run_state,version,generation,owner_id,lease_expires_at,created_by,updated_by,created_at,updated_at)
                VALUES (?,?,?,?,'RUNNING',0,1,?,?,?,?,?,?)
                """,id,planId,cycleId,plan.fingerprint(),activity.executorId(),stamp(now.plus(activity.leaseDuration())),actor,actor,stamp(now),stamp(now));
        for(int index=0;index<plan.actions().size();index++) {
            UUID action=plan.actions().get(index).actionId();
            store.jdbc.update("""
                    INSERT INTO plan_execution_step(run_id,ordinal,action_id,authorization_id,expected_operation_key,step_state)
                    VALUES (?,?,?,?,?,'READY')
                    """,id,index,action,authorizationIds.get(index),ExecutionOperationRegistry.operationKey(planId,action));
        }
        store.change(cycle,"E07_EJECUTANDO",EventType.PLAN_EXECUTION_STARTED,actor,command.reason().trim(),"plan-execution:"+id);
        return new Claim(id,planId,activity.executorId(),1,actor);
    }

    /** Reanuda solo una coordinación vencida sin despacho incierto, con permiso actual para lo pendiente. */
    @Transactional(timeout=10)
    public Claim resume(UUID planId,Control command,String actor) {
        store.lifecycle.validate(command); var locked=plans.lock(planId); var row=required(planId,true);
        expectVersion(row,command.expectedVersion()); unchanged(row,locked.plan());
        if(!"E07_EJECUTANDO".equals(locked.cycle().state())||!"RUNNING".equals(row.state()))
            throw fail(CONFLICT,"EXECUTION_NOT_RESUMABLE","La coordinación requiere recuperación o finalización");
        if(row.lease().isAfter(store.lifecycle.now())) throw fail(CONFLICT,"COORDINATOR_BUSY","Existe un coordinador vigente");
        validatePlan(locked.plan());
        for(var step:steps(row.id())) if(!"APPLIED".equals(step.state())) {
            if(!"READY".equals(step.state())||!"NONE".equals(observed(row,step).outcome()))
                throw fail(CONFLICT,"DISPATCH_UNCERTAIN","Un despacho previo exige recuperación sin envío");
            UUID current=authorize(locked.plan(),step.actionId());
            if(!current.equals(step.authorizationId())) throw fail(CONFLICT,"AUTHORIZATION_CHANGED","Cambió el permiso del paso");
        } else if(!"APPLIED".equals(observed(row,step).outcome())) {
            throw fail(CONFLICT,"RESULT_CHANGED","Un resultado previo requiere revisión");
        }
        var now=store.lifecycle.now(); long generation=row.generation()+1;
        store.jdbc.update("UPDATE plan_execution SET owner_id=?,generation=?,lease_expires_at=?,version=version+1,updated_by=?,updated_at=? WHERE run_id=?",
                activity.executorId(),generation,stamp(now.plus(activity.leaseDuration())),actor,stamp(now),row.id());
        store.change(locked.cycle(),locked.cycle().state(),EventType.PLAN_EXECUTION_RESUMED,actor,command.reason().trim(),"plan-execution:"+row.id());
        return new Claim(row.id(),planId,activity.executorId(),generation,actor);
    }

    /** Renueva únicamente una adquisición aún vigente; solo bloquea el run y no necesita locks de IR-009. */
    @Transactional(timeout=5)
    public void heartbeat(Claim claim) {
        var rows=store.jdbc.query("SELECT * FROM plan_execution WHERE run_id=? FOR UPDATE",this::map,claim.runId());
        if(rows.isEmpty()) throw fail(CONFLICT,"COORDINATOR_LOST","La coordinación no existe");
        var row=rows.getFirst(); owned(row,claim); var now=store.lifecycle.now();
        store.jdbc.update("UPDATE plan_execution SET lease_expires_at=? WHERE run_id=?",stamp(now.plus(activity.leaseDuration())),row.id());
    }

    /** Fija DISPATCHING antes de salir de la transacción; una caída posterior nunca permite reenviar a ciegas. */
    @Transactional(timeout=10)
    public Dispatch beginNext(Claim claim) {
        planActivity.requireOpen(claim.planId());
        var locked=plans.lock(claim.planId()); var row=required(claim.planId(),true);
        owned(row,claim); unchanged(row,locked.plan());
        if(!"E07_EJECUTANDO".equals(locked.cycle().state())) throw fail(CONFLICT,"CYCLE_NOT_EXECUTING","El ciclo no está ejecutando");
        if(!"APPROVED".equals(locked.plan().status())) throw fail(CONFLICT,"PLAN_NOT_APPROVED","El plan dejó de estar aprobado");
        for(var step:steps(row.id())) {
            if("APPLIED".equals(step.state())) {
                if(!"APPLIED".equals(observed(row,step).outcome())) throw fail(CONFLICT,"RESULT_CHANGED","Un resultado previo cambió");
                continue;
            }
            if(!"READY".equals(step.state())||!"NONE".equals(observed(row,step).outcome()))
                throw fail(CONFLICT,"DISPATCH_UNCERTAIN","No se puede despachar un paso incierto");
            var action=locked.plan().actions().get(step.ordinal());
            if(!action.actionId().equals(step.actionId())) throw fail(CONFLICT,"PLAN_CHANGED","El orden del plan cambió");
            resolve(action);
            if(!authorize(locked.plan(),step.actionId()).equals(step.authorizationId()))
                throw fail(CONFLICT,"AUTHORIZATION_CHANGED","Cambió el permiso del paso");
            store.jdbc.update("UPDATE plan_execution_step SET step_state='DISPATCHING' WHERE run_id=? AND ordinal=?",row.id(),step.ordinal());
            touch(row,claim.actor());
            store.change(locked.cycle(),locked.cycle().state(),EventType.PLAN_EXECUTION_PROGRESS,claim.actor(),"Despacho reservado","plan-execution:"+row.id()+":action:"+step.actionId());
            return new Dispatch(action,step.authorizationId());
        }
        store.jdbc.update("UPDATE plan_execution SET run_state='APPLIED',owner_id=NULL,lease_expires_at=NULL WHERE run_id=?",row.id());
        touch(row,claim.actor());
        store.change(locked.cycle(),locked.cycle().state(),EventType.PLAN_EXECUTION_PROGRESS,claim.actor(),"Todas las acciones aplicadas; verificación del ciclo pendiente","plan-execution:"+row.id());
        return null;
    }

    /** Comprueba la propiedad justo antes de invocar IR-009, sin mantener una transacción durante el efecto. */
    @Transactional(readOnly=true)
    public void assertOwner(Claim claim) {
        planActivity.requireOpen(claim.planId());
        var row=required(claim.planId(),false); owned(row,claim);
        var plan=plans.get(claim.planId(),false); unchanged(row,plan);
        if(!"APPROVED".equals(plan.status())||!"E07_EJECUTANDO".equals(store.lifecycle.getCycle(row.cycle()).state()))
            throw fail(CONFLICT,"EXECUTION_CONTEXT_CHANGED","El plan o el ciclo dejaron de admitir ejecución");
    }

    /** Importa únicamente un resultado aplicado ya persistido por IR-009 y correspondiente a la clave esperada. */
    @Transactional(timeout=10)
    public void applied(Claim claim,UUID actionId) {
        var locked=plans.lock(claim.planId()); var row=required(claim.planId(),true); owned(row,claim); unchanged(row,locked.plan());
        var step=steps(row.id()).stream().filter(s->s.actionId().equals(actionId)).findFirst().orElseThrow();
        var fact=observed(row,step);
        if(!"DISPATCHING".equals(step.state())||!"APPLIED".equals(fact.outcome())) throw fail(CONFLICT,"RESULT_UNCONFIRMED","IR-009 no confirma el efecto aplicado");
        recordApplied(row,step,fact); touch(row,claim.actor());
        store.change(locked.cycle(),locked.cycle().state(),EventType.PLAN_EXECUTION_PROGRESS,claim.actor(),"Efecto aplicado registrado por IR-009","plan-execution:"+row.id()+":action:"+actionId);
    }

    /** Bloquea continuación ante cualquier fallo; un propietario vencido no puede cambiar la coordinación. */
    @Transactional(timeout=10)
    public void pause(Claim claim) {
        var locked=plans.lock(claim.planId()); var row=required(claim.planId(),true); owned(row,claim);
        store.jdbc.update("UPDATE plan_execution_step SET step_state='RECOVERY_REQUIRED' WHERE run_id=? AND step_state='DISPATCHING'",row.id());
        pauseRow(row,locked.cycle(),claim.actor(),"Despacho interrumpido o resultado no confirmado");
    }

    /**
     * Recuperación mínima por APPROVER: importa hechos locales sin ejecutar, consultar
     * al destinatario, renovar permisos o reintentar UNKNOWN. Puede usarse con EXECUTE_ACTION vencido.
     */
    @Transactional(timeout=10)
    public Run recover(UUID planId,Control command,String actor) {
        store.lifecycle.validate(command); var locked=plans.lock(planId); var row=required(planId,true);
        expectVersion(row,command.expectedVersion()); unchanged(row,locked.plan());
        if("RUNNING".equals(row.state())&&row.lease().isAfter(store.lifecycle.now())) throw fail(CONFLICT,"COORDINATOR_BUSY","El coordinador sigue vigente");
        if(!List.of("E07_EJECUTANDO","E13_RECUPERANDO").contains(locked.cycle().state()))
            throw fail(CONFLICT,"CYCLE_NOT_RECOVERABLE","La etapa no admite esta recuperación mínima");
        boolean allApplied=true;
        for(var step:steps(row.id())) {
            var fact=observed(row,step);
            if("APPLIED".equals(fact.outcome())) recordApplied(row,step,fact);
            else {
                allApplied=false;
                if(!"READY".equals(step.state())||!"NONE".equals(fact.outcome()))
                    store.jdbc.update("UPDATE plan_execution_step SET step_state='RECOVERY_REQUIRED' WHERE run_id=? AND ordinal=?",row.id(),step.ordinal());
            }
        }
        if(allApplied) {
            store.jdbc.update("UPDATE plan_execution SET run_state='APPLIED',owner_id=NULL,lease_expires_at=NULL,generation=generation+1 WHERE run_id=?",row.id());
            touch(row,actor);
            store.change(locked.cycle(),locked.cycle().state(),EventType.PLAN_EXECUTION_RECOVERED,actor,command.reason().trim(),"plan-execution:"+row.id());
        } else pauseRow(row,locked.cycle(),actor,command.reason().trim());
        return view(planId);
    }

    /**
     * Finaliza bajo el bloqueo del plan que comparten evidencia e intentos IR-009.
     * Importa hechos confirmados, invalida propiedad anterior y cambia a E08 junto
     * con versión, evento y transición. No exige EXECUTE_ACTION ni realiza IO.
     * Una contradicción posterior sigue visible mediante requiresReview.
     */
    @Transactional(timeout=10)
    public Run finish(UUID planId,Finish command,String actor) {
        store.lifecycle.validate(command);
        var locked=plans.lock(planId); var row=required(planId,true);
        expectVersion(row,command.expectedVersion()); unchanged(row,locked.plan());
        var cycle=locked.cycle();
        if(cycle.version()!=command.expectedCycleVersion())
            throw fail(CONFLICT,"VERSION_CONFLICT","La versión del ciclo cambió");
        if(!List.of("E07_EJECUTANDO","E13_RECUPERANDO").contains(cycle.state()))
            throw fail(CONFLICT,"EXECUTION_NOT_FINALIZABLE","La etapa no admite finalizar la ejecución");
        if("RUNNING".equals(row.state())&&row.lease().isAfter(store.lifecycle.now()))
            throw fail(CONFLICT,"COORDINATOR_BUSY","El coordinador sigue vigente");
        if(!"APPROVED".equals(locked.plan().status()))
            throw fail(CONFLICT,"PLAN_NOT_APPROVED","El plan dejó de estar aprobado");
        var steps=steps(row.id());
        if(steps.isEmpty()||steps.size()!=locked.plan().actions().size())
            throw fail(CONFLICT,"RESULT_UNCONFIRMED","La secuencia del plan está incompleta");
        var facts=new ArrayList<Fact>();
        for(var step:steps) {
            if(!locked.plan().actions().get(step.ordinal()).actionId().equals(step.actionId()))
                throw fail(CONFLICT,"PLAN_CHANGED","El orden del plan cambió");
            var fact=observed(row,step);
            if(!"APPLIED".equals(fact.outcome()))
                throw fail(CONFLICT,"RESULT_UNCONFIRMED","Todas las acciones requieren resultado aplicado sin contradicciones");
            facts.add(fact);
        }
        for(int index=0;index<steps.size();index++) recordApplied(row,steps.get(index),facts.get(index));
        store.jdbc.update("UPDATE plan_execution SET run_state='APPLIED',owner_id=NULL,lease_expires_at=NULL,generation=generation+1 WHERE run_id=?",row.id());
        touch(row,actor);
        store.change(cycle,"E08_VERIFICANDO",EventType.PLAN_EXECUTION_FINALIZED,actor,command.reason().trim(),"plan-execution:"+row.id());
        return view(planId);
    }

    /** Lectura sin permiso de ejecución vigente ni cambios de estado, con proyección conservadora de IR-009. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Run view(UUID planId) {
        var plan=plans.get(planId,false); var row=required(planId,false); var cycle=store.lifecycle.getCycle(row.cycle());
        var projected=new ArrayList<Step>();
        boolean review="RECOVERY_REQUIRED".equals(row.state())||!plan.fingerprint().equals(row.fingerprint())
                ||("RUNNING".equals(row.state())&&!row.lease().isAfter(store.lifecycle.now()));
        for(var step:steps(row.id())) {
            var fact=observed(row,step);
            if(List.of("UNKNOWN","EXPIRED","CONFLICT","UNCONFIRMED").contains(fact.outcome())
                    ||("APPLIED".equals(step.state())&&(!"APPLIED".equals(fact.outcome())||!Objects.equals(step.resultReference(),fact.reference())))) review=true;
            projected.add(new Step(step.ordinal(),step.actionId(),step.authorizationId(),step.operationKey(),step.state(),
                    fact.executionId()==null?step.executionId():fact.executionId(),step.resultReference(),fact.outcome()));
        }
        return new Run(row.id(),row.plan(),row.cycle(),row.state(),cycle.state(),row.version(),row.generation(),row.lease(),
                row.createdBy(),row.updatedBy(),row.createdAt(),row.updatedAt(),review,projected,incidents.findForRun(row.id()));
    }

    /** Valida estado aprobado y todos los enlaces sin producir efectos ni reservar claves IR-009. */
    private void validatePlan(InterventionViews.Plan plan) {
        planActivity.requireOpen(plan.planId());
        if(!"APPROVED".equals(plan.status())||plan.actions().isEmpty()) throw fail(CONFLICT,"PLAN_NOT_APPROVED","Se requiere un plan aprobado con acciones");
        for(var action:plan.actions()) resolve(action);
    }

    /** Rechaza planes descriptivos y configuraciones alteradas desde la aprobación. */
    private void resolve(InterventionViews.PlanAction action) {
        try { catalog.resolve(action.execution()); }
        catch(RuntimeException invalid) { throw fail(CONFLICT,"EXECUTABLE_BINDING_INVALID","Definición ejecutable ausente o enlace no instalado"); }
    }

    /** Bloquea el permiso específico y revalida huella y política productiva antes de cada paso. */
    private UUID authorize(InterventionViews.Plan plan,UUID action) {
        var ids=store.jdbc.query("SELECT authorization_id,request_fingerprint FROM authorizations WHERE plan_id=? AND action_id=? AND scope='EXECUTE_ACTION' FOR UPDATE",
                (rs,row)->Map.entry(rs.getObject(1,UUID.class),Objects.toString(rs.getString(2),"")),plan.planId(),action);
        if(ids.size()!=1||!plan.fingerprint().equals(ids.getFirst().getValue())||!permissions.evaluate(ids.getFirst().getKey(),
                new AuthorizationUseRequest(plan.planId(),action,"EXECUTE_ACTION",store.lifecycle.now())).allowed())
            throw fail(CONFLICT,"EXECUTION_AUTHORIZATION_INVALID","El permiso de ejecución no está vigente o no corresponde al contenido");
        return ids.getFirst().getKey();
    }

    /** Resultado observado sin inferir que ausencia o timeout equivalen a NO_EFFECT. */
    private record Fact(String outcome,UUID executionId,String reference) { }

    /** Comprueba también el snapshot de la operación; una clave correcta con bytes distintos es conflicto. */
    private Fact observed(Row row,Step step) {
        var latest=outcomes.findLatest(row.plan(),step.actionId());
        if(latest.isEmpty()) return new Fact("NONE",null,null);
        var fact=latest.get();
        if("READY".equals(step.state())) return new Fact("CONFLICT",fact.executionId(),null);
        if(!step.operationKey().equals(fact.operationKey())) return new Fact("CONFLICT",fact.executionId(),null);
        var action=plans.get(row.plan(),false).actions().get(step.ordinal());
        var request=action.execution()==null?null:action.execution().request();
        var operations=store.jdbc.query("SELECT destination_id,request_fingerprint,request_media_type,request_body FROM execution_operation WHERE operation_key=?",
                (rs,index)-> {
                    if(rs.getString(1)==null||rs.getString(3)==null||rs.getString(4)==null) return null;
                    var persisted=new ExecutionRequest(rs.getString(1),rs.getString(3),rs.getString(4));
                    return persisted.fingerprint().equals(rs.getString(2))?persisted:null;
                },step.operationKey());
        if(request==null||operations.size()!=1||!request.equals(operations.getFirst())) return new Fact("CONFLICT",fact.executionId(),null);
        String outcome=fact.outcome().name();
        if("APPLIED".equals(outcome)&&(fact.resultReference()==null||fact.resultReference().isBlank())) outcome="UNCONFIRMED";
        if("APPLIED".equals(step.state())&&!Objects.equals(step.resultReference(),fact.resultReference())) outcome="CONFLICT";
        return new Fact(outcome,fact.executionId(),fact.resultReference());
    }

    /** Snapshot persistido de pasos; observedOutcome solo se rellena al consultar IR-009. */
    private List<Step> steps(UUID run) {
        return store.jdbc.query("SELECT * FROM plan_execution_step WHERE run_id=? ORDER BY ordinal",(rs,row)->new Step(
                rs.getInt("ordinal"),rs.getObject("action_id",UUID.class),rs.getObject("authorization_id",UUID.class),rs.getObject("expected_operation_key",UUID.class),
                rs.getString("step_state"),rs.getObject("execution_id",UUID.class),rs.getString("result_reference"),"NONE"),run);
    }

    /** Marca un paso usando únicamente una referencia ya acreditada por la proyección IR-009. */
    private void recordApplied(Row row,Step step,Fact fact) {
        store.jdbc.update("UPDATE plan_execution_step SET step_state='APPLIED',execution_id=?,result_reference=? WHERE run_id=? AND ordinal=?",
                fact.executionId(),fact.reference(),row.id(),step.ordinal());
    }

    /** Pausa conservadora con transición válida a E13; no presume rollback de efectos externos. */
    private void pauseRow(Row row,LifecycleViews.CycleView cycle,String actor,String reason) {
        store.jdbc.update("UPDATE plan_execution SET run_state='RECOVERY_REQUIRED',owner_id=NULL,lease_expires_at=NULL,generation=generation+1 WHERE run_id=?",row.id());
        touch(row,actor);
        store.change(cycle,"E13_RECUPERANDO",EventType.PLAN_EXECUTION_PAUSED,actor,reason,"plan-execution:"+row.id());
    }

    /** Incrementa versión solo por cambios de dominio; heartbeat no invalida comandos continuamente. */
    private void touch(Row row,String actor) {
        store.jdbc.update("UPDATE plan_execution SET version=version+1,updated_by=?,updated_at=? WHERE run_id=?",actor,stamp(store.lifecycle.now()),row.id());
    }

    /** Verifica credencial, estado y vigencia después de adquirir bloqueos. */
    private void owned(Row row,Claim claim) {
        if(!row.id().equals(claim.runId())||!"RUNNING".equals(row.state())||row.generation()!=claim.generation()
                ||!Objects.equals(row.owner(),claim.owner())||row.lease()==null||!row.lease().isAfter(store.lifecycle.now()))
            throw fail(CONFLICT,"COORDINATOR_LOST","La propiedad de coordinación no está vigente");
    }

    /** Impide importar resultados o ejecutar sobre contenido diferente al reservado. */
    private static void unchanged(Row row,InterventionViews.Plan plan) {
        if(!row.fingerprint().equals(plan.fingerprint())) throw fail(CONFLICT,"PLAN_CHANGED","El contenido aprobado cambió");
    }

    /** Control optimista para comandos humanos sobre el recurso de coordinación. */
    private static void expectVersion(Row row,long version) {
        if(row.version()!=version) throw fail(CONFLICT,"RUN_VERSION_CONFLICT","La versión de ejecución cambió");
    }

    /** Consulta opcional, con bloqueo solo dentro de una transacción de escritura. */
    private List<Row> rows(UUID plan,boolean lock) {
        return store.jdbc.query("SELECT * FROM plan_execution WHERE plan_id=?"+(lock?" FOR UPDATE":""),this::map,plan);
    }

    /** Inexistencia explícita para lectura y control de un run. */
    private Row required(UUID plan,boolean lock) {
        var rows=rows(plan,lock);
        if(rows.isEmpty()) throw fail(NOT_FOUND,"PLAN_EXECUTION_NOT_FOUND","El plan no tiene coordinación");
        return rows.getFirst();
    }

    /** Reconstruye la identidad de coordinación sin objetos modificables de persistencia. */
    private Row map(ResultSet rs,int ignored) throws SQLException {
        var lease=rs.getTimestamp("lease_expires_at");
        return new Row(rs.getObject("run_id",UUID.class),rs.getObject("plan_id",UUID.class),rs.getObject("cycle_id",UUID.class),
                rs.getString("plan_fingerprint"),rs.getString("run_state"),rs.getLong("version"),rs.getLong("generation"),rs.getString("owner_id"),
                lease==null?null:lease.toInstant(),rs.getString("created_by"),rs.getString("updated_by"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }

    /** Conversión consistente con las columnas TIMESTAMP(6). */
    private static Timestamp stamp(Instant time) { return Timestamp.from(time); }
}
