package com.miec.orchestration;

import com.miec.action.ExecutionOutcomeQueryService;
import com.miec.authorization.AuthorizationPersistenceService;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;
import com.miec.domain.EventType;
import com.miec.execution.ExecutableActionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;
import static com.miec.orchestration.PlanExecutionContracts.*;

/**
 * Permisos humanos y precondiciones locales de recuperación. Conserva los permisos,
 * decisiones e intentos IR-009 existentes; no crea otro motor de autorización.
 * Bloquea sistema, ciclo, plan, run y finalmente autorización. Nunca hace IO remoto.
 */
@Service
class PlanRecoveryStore {
    private final InterventionSupport store;
    private final InterventionPlans plans;
    private final InterventionAuthorizations authorizations;
    private final AuthorizationPersistenceService validity;
    private final PlanExecutionStore runs;
    private final ExecutableActionCatalog catalog;
    private final ExecutionOutcomeQueryService outcomes;

    /** Reutiliza reglas de preparación, lectura de hechos y catálogo de servidor. */
    PlanRecoveryStore(InterventionSupport store,InterventionPlans plans,InterventionAuthorizations authorizations,
            AuthorizationPersistenceService validity,PlanExecutionStore runs,ExecutableActionCatalog catalog,ExecutionOutcomeQueryService outcomes) {
        this.store=store;this.plans=plans;this.authorizations=authorizations;this.validity=validity;
        this.runs=runs;this.catalog=catalog;this.outcomes=outcomes;
    }

    /** Snapshot interno para atribuir la consulta y resolución al intento observado. */
    record Review(UUID executionId,UUID evidenceId) { }

    /** Solicita permiso acotado sin concederlo, renovar ejecución ni modificar el contenido aprobado. */
    @Transactional(timeout=10)
    public InterventionViews.Authorization request(UUID planId,RequestReconciliation command,String actor) {
        store.lifecycle.validate(command);
        var locked=plans.lock(planId);var run=lockRun(planId,command.expectedVersion());
        eligible(locked,run,command.actionId());
        if(store.jdbc.queryForObject("""
                SELECT COUNT(*) FROM authorizations WHERE plan_id=? AND action_id=? AND scope='RECONCILE_ACTION'
                AND status IN ('PENDING','APPROVED') AND expires_at>CURRENT_TIMESTAMP
                """,Integer.class,planId,command.actionId())>0)
            throw fail(CONFLICT,"RECONCILIATION_AUTHORIZATION_EXISTS","Ya existe una solicitud o permiso vigente para reconciliar");
        UUID id=UUID.randomUUID();var now=store.lifecycle.now();
        store.jdbc.update("""
                INSERT INTO authorizations(authorization_id,plan_id,action_id,requested_by,scope,status,issued_at,expires_at,
                    authorization_policy_version_id,created_at,request_fingerprint,version)
                VALUES (?,?,?,?,'RECONCILE_ACTION','PENDING',?,?,'MVP-RECOVERY-01',?,?,0)
                """,id,planId,command.actionId(),actor,Timestamp.from(now),Timestamp.from(now.plusSeconds(command.validForSeconds())),Timestamp.from(now),locked.plan().fingerprint());
        audit(run,locked.cycle(),actor,command.reason(),EventType.PLAN_EXECUTION_RECONCILIATION_REQUESTED,id);
        return authorizations.get(id);
    }

    /** Decide una sola solicitud, con separación humana y sin alterar permisos EXECUTE_ACTION. */
    @Transactional(timeout=10)
    public InterventionViews.Authorization decide(UUID id,InterventionCommands.Decide command,boolean approve,String actor) {
        store.lifecycle.validate(command);var initial=authorizations.get(id);var locked=plans.lock(initial.planId());
        var run=lockRun(initial.planId(),null);
        store.jdbc.queryForObject("SELECT authorization_id FROM authorizations WHERE authorization_id=? FOR UPDATE",UUID.class,id);
        var current=authorizations.get(id);
        if(!"RECONCILE_ACTION".equals(current.scope())) throw fail(CONFLICT,"INVALID_RECONCILIATION_AUTHORIZATION","La solicitud tiene otro alcance");
        if(current.version()!=command.expectedVersion()) throw fail(CONFLICT,"AUTHORIZATION_VERSION_CONFLICT","La solicitud cambió");
        if(!"PENDING".equals(current.status())||current.decision()!=null) throw fail(CONFLICT,"AUTHORIZATION_NOT_PENDING","La solicitud ya fue resuelta");
        if(actor.equalsIgnoreCase(current.requestedBy())) throw fail(FORBIDDEN,"SELF_APPROVAL_FORBIDDEN","Solicitante y aprobador deben ser distintos");
        eligible(locked,run,current.actionId());
        var fingerprint=store.jdbc.queryForObject("SELECT request_fingerprint FROM authorizations WHERE authorization_id=?",String.class,id);
        if(!locked.plan().fingerprint().equals(fingerprint)) throw fail(CONFLICT,"PLAN_CHANGED","Cambió el contenido solicitado");
        var now=store.lifecycle.now();
        if(approve&&(current.expiresAt()==null||!current.expiresAt().isAfter(now)||current.issuedAt().isAfter(now)))
            throw fail(CONFLICT,"AUTHORIZATION_EXPIRED","La solicitud dejó de estar vigente");
        store.jdbc.update("UPDATE authorizations SET status=?,approved_by=?,version=version+1 WHERE authorization_id=?",
                approve?"APPROVED":"REJECTED",approve?actor:null,id);
        var event=audit(run,locked.cycle(),actor,command.reason(),approve?EventType.PLAN_EXECUTION_RECONCILIATION_APPROVED:EventType.PLAN_EXECUTION_RECONCILIATION_REJECTED,id);
        store.jdbc.update("INSERT INTO authorization_decision VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID(),id,approve?"APPROVED":"REJECTED",actor,command.reason().trim(),Timestamp.from(now),fingerprint,event);
        return authorizations.get(id);
    }

    /**
     * Revalida antes y después del GET. La autorización se comprueba de nuevo al
     * persistir evidencia y decisión en IR-009. No mantiene locks durante la red.
     */
    @Transactional(timeout=10)
    public Review prepare(UUID planId,Reconcile command,String actor) {
        store.lifecycle.validate(command);var locked=plans.lock(planId);var run=lockRun(planId,command.expectedVersion());
        var step=eligible(locked,run,command.actionId());
        var permission=authorizations.get(command.authorizationId());
        store.jdbc.queryForObject("SELECT authorization_id FROM authorizations WHERE authorization_id=? FOR UPDATE",UUID.class,command.authorizationId());
        permission=authorizations.get(command.authorizationId());
        if(!actor.equals(permission.approvedBy())||!validity.evaluate(command.authorizationId(),new AuthorizationUseRequest(planId,command.actionId(),"RECONCILE_ACTION",store.lifecycle.now())).allowed())
            throw fail(CONFLICT,"RECONCILIATION_AUTHORIZATION_INVALID","El revisor no dispone de permiso vigente para reconciliar esta acción");
        var fingerprint=store.jdbc.queryForObject("SELECT request_fingerprint FROM authorizations WHERE authorization_id=?",String.class,command.authorizationId());
        if(!locked.plan().fingerprint().equals(fingerprint)) throw fail(CONFLICT,"PLAN_CHANGED","El permiso no corresponde al contenido aprobado");
        UUID proof=UUID.nameUUIDFromBytes(("MVP-RECOVERY-01:"+step.executionId()+":"+command.authorizationId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Review(step.executionId(),proof);
    }

    /** Valida contexto, propiedad vencida, snapshot y resultado incierto, sin aceptar READY como efecto ausente. */
    private Step eligible(InterventionPlans.Locked locked,Run run,UUID actionId) {
        if(!List.of("E07_EJECUTANDO","E13_RECUPERANDO").contains(locked.cycle().state())||!"APPROVED".equals(locked.plan().status()))
            throw fail(CONFLICT,"CYCLE_NOT_RECOVERABLE","El contexto no admite reconciliación");
        if("RUNNING".equals(run.state())&&run.leaseExpiresAt().isAfter(store.lifecycle.now()))
            throw fail(CONFLICT,"COORDINATOR_BUSY","El coordinador sigue vigente");
        var action=locked.plan().actions().stream().filter(a->a.actionId().equals(actionId)).findFirst()
                .orElseThrow(()->fail(BAD_REQUEST,"ACTION_NOT_PLANNED","La acción no pertenece al plan"));
        try {catalog.resolve(action.execution());}
        catch(RuntimeException invalid){throw fail(CONFLICT,"EXECUTABLE_BINDING_INVALID","El enlace aprobado no está disponible");}
        var step=run.steps().stream().filter(s->s.actionId().equals(actionId)).findFirst().orElseThrow();
        if(step.executionId()==null||!List.of("UNKNOWN","EXPIRED","APPLIED").contains(step.observedOutcome())||"READY".equals(step.state()))
            throw fail(CONFLICT,"ATTEMPT_NOT_RECONCILABLE","El intento no admite esta recuperación");
        var fact=outcomes.findAttempt(step.executionId()).orElseThrow();
        if(!List.of("UNKNOWN","IN_PROGRESS").contains(fact.originalStatus())||("IN_PROGRESS".equals(fact.originalStatus())&&fact.leaseExpiresAt()!=null&&fact.leaseExpiresAt().isAfter(store.lifecycle.now())))
            throw fail(CONFLICT,"ATTEMPT_NOT_RECONCILABLE","Solo se reconcilian intentos inciertos sin propietario vigente");
        return step;
    }

    /** Bloquea el run y rechaza comandos humanos sobre una revisión desactualizada. */
    private Run lockRun(UUID planId,Long version) {
        var fingerprints=store.jdbc.query("SELECT plan_fingerprint FROM plan_execution WHERE plan_id=? FOR UPDATE",(rs,row)->rs.getString(1),planId);
        if(fingerprints.isEmpty()) throw fail(NOT_FOUND,"PLAN_EXECUTION_NOT_FOUND","El plan no tiene coordinación");
        if(!fingerprints.getFirst().equals(plans.get(planId,false).fingerprint())) throw fail(CONFLICT,"PLAN_CHANGED","Cambió el contenido de la coordinación");
        var run=runs.view(planId);
        if(version!=null&&run.version()!=version) throw fail(CONFLICT,"RUN_VERSION_CONFLICT","La versión de ejecución cambió");
        return run;
    }

    /** Guarda solicitud o decisión y su evento con las versiones del run y ciclo en una sola transacción. */
    private UUID audit(Run run,LifecycleViews.CycleView cycle,String actor,String reason,EventType type,UUID authorization) {
        store.jdbc.update("UPDATE plan_execution SET version=version+1,updated_by=?,updated_at=? WHERE run_id=?",actor,Timestamp.from(store.lifecycle.now()),run.runId());
        return store.change(cycle,cycle.state(),type,actor,reason.trim(),"authorization:"+authorization);
    }
}
