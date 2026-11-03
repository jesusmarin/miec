package com.miec.orchestration;

import com.miec.domain.EventType;
import com.miec.authorization.AuthorizationValidityPolicy;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationSnapshot;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.miec.orchestration.InterventionCommands.*;
import static com.miec.orchestration.InterventionViews.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Flujo interno; solo la fachada autenticada lo expone fuera del paquete de aplicación. */
@Service
class InterventionWorkflow {
    private final InterventionSupport store;
    private final InterventionPlans plans;
    private final InterventionAuthorizations authorizations;
    private final AuthorizationValidityPolicy validity;
    private final MeasurementQueries measurements;
    private final com.miec.execution.ExecutableActionCatalog executables;

    /** Recibe coordinación transaccional compartida con el ciclo. */
    InterventionWorkflow(InterventionSupport store,InterventionPlans plans,InterventionAuthorizations authorizations,AuthorizationValidityPolicy validity,MeasurementQueries measurements,com.miec.execution.ExecutableActionCatalog executables) {
        this.store=store; this.plans=plans; this.authorizations=authorizations; this.validity=validity; this.measurements=measurements; this.executables=executables;
    }

    /** Registra un objetivo del mismo sistema y completa E01 a E02. */
    @Transactional(timeout=10)
    public Preparation initialize(UUID cycleId,Initialize command,String actor) {
        store.lifecycle.validate(command); actor=LifecycleService.actor(actor);
        var cycle=store.lock(cycleId); store.expect(cycle,command.expectedVersion(),"E01_INICIALIZANDO");
        UUID objective=UUID.randomUUID(); var time=Timestamp.from(store.lifecycle.now());
        store.jdbc.update("""
                INSERT INTO objective(objective_id,system_id,name,success_criteria,status,created_by,created_at)
                VALUES (?,?,?,?,'ACTIVE',?,?)
                """,objective,cycle.systemId(),command.objective().trim(),command.successCriteria().trim(),actor,time);
        store.jdbc.update("INSERT INTO cycle_objective(cycle_id,objective_id,system_id) VALUES (?,?,?)",cycleId,objective,cycle.systemId());
        store.change(cycle,"E02_OBSERVANDO",EventType.CYCLE_INITIALIZED,actor,command.reason().trim(),"objective:"+objective);
        return new Preparation(store.lifecycle.getCycle(cycleId),objective);
    }

    /** Registra un reporte humano no verificado y pasa de E02 a E03. */
    @Transactional(timeout=10)
    public Preparation observe(UUID cycleId,Observe command,String actor) {
        store.lifecycle.validate(command); actor=LifecycleService.actor(actor);
        var cycle=store.lock(cycleId); store.expect(cycle,command.expectedVersion(),"E02_OBSERVANDO");
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM indicator i JOIN cycle_objective co ON co.objective_id=i.objective_id WHERE co.cycle_id=? AND i.comparison_operator IS NOT NULL",Integer.class,cycleId)>0)
            throw fail(CONFLICT,"EXPLICIT_OBSERVATION_CLOSURE_REQUIRED","Use mediciones y cierre explícito para este ciclo");
        UUID evidence=UUID.randomUUID(); var time=Timestamp.from(store.lifecycle.now());
        store.jdbc.update("""
                INSERT INTO evidence(evidence_id,cycle_id,source_type,source_reference,description,captured_at,provenance,quality_status,limitations,created_at)
                VALUES (?,?,'HUMAN_REPORT',?,?,?,?,'UNVERIFIED','Reporte manual; no acredita por sí solo una conclusión',?)
                """,evidence,cycleId,command.sourceReference(),command.description().trim(),time,actor,time);
        store.change(cycle,"E03_DIAGNOSTICANDO",EventType.OBSERVATION_RECORDED,actor,command.reason().trim(),"evidence:"+evidence);
        return new Preparation(store.lifecycle.getCycle(cycleId),evidence);
    }

    /** Conserva diagnóstico manual y fuente del ciclo; no declara verificación o éxito automático. */
    @Transactional(timeout=10)
    public Preparation diagnose(UUID cycleId,Diagnose command,String actor) {
        store.lifecycle.validate(command); actor=LifecycleService.actor(actor);
        var cycle=store.lock(cycleId); store.expect(cycle,command.expectedVersion(),"E03_DIAGNOSTICANDO");
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM evidence WHERE evidence_id=? AND cycle_id=?",Integer.class,command.evidenceId(),cycleId)!=1) {
            throw fail(BAD_REQUEST,"INVALID_EVIDENCE","La observación no pertenece al ciclo");
        }
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM indicator i JOIN cycle_objective co ON co.objective_id=i.objective_id WHERE co.cycle_id=? AND i.comparison_operator IS NOT NULL",Integer.class,cycleId)>0
                && store.jdbc.queryForObject("SELECT COUNT(*) FROM evidence WHERE evidence_id=? AND source_reference='MVP-MEASURE-CLOSURE'",Integer.class,command.evidenceId())!=1)
            throw fail(BAD_REQUEST,"BASELINE_CLOSURE_REQUIRED","El diagnóstico debe referenciar el cierre de línea base");
        UUID diagnosis=UUID.randomUUID();
        store.jdbc.update("INSERT INTO cycle_diagnosis VALUES (?,?,?,?,?,?)",diagnosis,cycleId,command.evidenceId(),command.summary().trim(),actor,Timestamp.from(store.lifecycle.now()));
        store.change(cycle,"E04_PLANIFICANDO",EventType.DIAGNOSIS_RECORDED,actor,command.reason().trim(),"diagnosis:"+diagnosis);
        return new Preparation(store.lifecycle.getCycle(cycleId),diagnosis);
    }

    /** Propone un plan inmutable y acciones v01 solo cuando existe contexto de planificación. */
    @Transactional(timeout=10)
    public Plan propose(UUID cycleId,ProposePlan command,String actor) {
        store.lifecycle.validate(command); actor=LifecycleService.actor(actor);
        var cycle=store.lock(cycleId); store.expect(cycle,command.expectedVersion(),"E04_PLANIFICANDO");
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM cycle_objective WHERE cycle_id=? AND objective_id=?",Integer.class,cycleId,command.objectiveId())!=1) {
            throw fail(BAD_REQUEST,"INVALID_OBJECTIVE","El objetivo no pertenece al ciclo");
        }
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM intervention_plan WHERE cycle_id=? AND status IN ('PROPOSED','AWAITING_AUTHORIZATION','APPROVED')",Integer.class,cycleId)>0) {
            throw fail(CONFLICT,"ACTIVE_PLAN_EXISTS","El ciclo ya tiene un plan activo");
        }
        var diagnoses=store.jdbc.query("SELECT diagnosis_id FROM cycle_diagnosis WHERE cycle_id=? ORDER BY created_at DESC,diagnosis_id DESC LIMIT 1",
                (rs,row)->rs.getObject(1,UUID.class),cycleId);
        if(diagnoses.isEmpty()) throw fail(CONFLICT,"DIAGNOSIS_REQUIRED","Se necesita un diagnóstico del ciclo");
        UUID id=UUID.randomUUID();
        var actions=new ArrayList<PlanAction>();
        for(var action:command.actions()) {
            com.miec.execution.ExecutableActions.Definition definition=null;
            if(action.execution()!=null) {
                try { definition=executables.capture(action.execution()); }
                catch(RuntimeException invalid) { throw fail(BAD_REQUEST,"INVALID_EXECUTABLE_ACTION","El adaptador o la solicitud ejecutable no cumplen el contrato instalado"); }
            }
            actions.add(new PlanAction(UUID.randomUUID(),action.actionType(),action.description().trim(),definition));
        }
        String document=plans.json.writeValueAsString(Map.of("schemaVersion","01","actions",actions));
        store.jdbc.update("""
                INSERT INTO intervention_plan(plan_id,cycle_id,objective_ids,actions,expected_outcomes,rollback_strategy,
                    authorization_requirements,status,version_id,created_at,created_by,revision,diagnosis_id)
                VALUES (?,?,? FORMAT JSON,? FORMAT JSON,?,?,'EXECUTE_ACTION','PROPOSED','MVP-02:1',?,?,0,?)
                """,id,cycleId,plans.json.writeValueAsString(List.of(command.objectiveId())),document,
                command.expectedOutcomes().trim(),command.rollbackStrategy().trim(),Timestamp.from(store.lifecycle.now()),actor,diagnoses.getFirst());
        store.change(cycle,cycle.state(),EventType.PLAN_PROPOSED,actor,"Plan de intervención propuesto","plan:"+id);
        return plans.get(id,false);
    }

    /** Recupera el contenido que el revisor debe evaluar, sin cambiarlo. */
    @Transactional(readOnly=true)
    public Plan getPlan(UUID planId) { return plans.get(planId,false); }

    /** Permite al revisor leer el contexto humano referenciado, con una instantánea de lectura consistente. */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReviewContext reviewContext(UUID planId) {
        var plan=plans.get(planId,false);
        if(plan.objectiveId()==null||plan.diagnosisId()==null) throw fail(CONFLICT,"CONTEXT_UNAVAILABLE","El plan histórico no tiene contexto de revisión MVP-02");
        var objectives=store.jdbc.query("""
                SELECT o.objective_id,o.name,o.success_criteria FROM objective o
                JOIN cycle_objective co ON co.objective_id=o.objective_id
                WHERE co.cycle_id=? AND o.objective_id=?
                """,(rs,row)->new ReviewObjective(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),plan.cycleId(),plan.objectiveId());
        var diagnoses=store.jdbc.query("""
                SELECT d.diagnosis_id,d.summary,d.created_by,e.evidence_id,e.description,e.source_reference,e.quality_status,e.limitations
                FROM cycle_diagnosis d JOIN evidence e ON e.evidence_id=d.evidence_id
                WHERE d.diagnosis_id=? AND d.cycle_id=?
                """,(rs,row)->new ReviewDiagnosis(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),plan.diagnosisId(),plan.cycleId());
        if(objectives.size()!=1||diagnoses.size()!=1) throw fail(CONFLICT,"CONTEXT_UNAVAILABLE","No se puede reconstruir el contexto del plan");
        return new ReviewContext(plan.planId(),plan.cycleId(),plan.fingerprint(),objectives.getFirst(),diagnoses.getFirst(),measurements.context(plan.cycleId()));
    }

    /** Solicita permiso para una acción existente; su huella se obtiene del contenido persistido. */
    @Transactional(timeout=10)
    public Authorization requestAuthorization(UUID planId,RequestAuthorization command,String actor) {
        store.lifecycle.validate(command); actor=LifecycleService.actor(actor);
        var locked=plans.lock(planId); var plan=locked.plan(); var cycle=locked.cycle();
        if(plan.version()!=command.expectedPlanVersion()) throw fail(CONFLICT,"PLAN_VERSION_CONFLICT","La revisión del plan cambió");
        if(!List.of("E04_PLANIFICANDO","E05_ESPERANDO_AUTORIZACION").contains(cycle.state())
                ||!List.of("PROPOSED","AWAITING_AUTHORIZATION").contains(plan.status())) {
            throw fail(CONFLICT,"PLAN_NOT_REQUESTABLE","El plan no admite solicitudes");
        }
        if(plan.actions().stream().noneMatch(action->action.actionId().equals(command.actionId()))) {
            throw fail(BAD_REQUEST,"ACTION_NOT_PLANNED","La acción no pertenece al plan");
        }
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE plan_id=? AND action_id=?",Integer.class,planId,command.actionId())>0) {
            throw fail(CONFLICT,"AUTHORIZATION_EXISTS","La acción ya tiene una solicitud; debe resolverse su revisión");
        }
        UUID id=UUID.randomUUID(); var time=store.lifecycle.now();
        store.jdbc.update("""
                INSERT INTO authorizations(authorization_id,plan_id,action_id,requested_by,scope,status,issued_at,expires_at,
                    authorization_policy_version_id,created_at,request_fingerprint,version)
                VALUES (?,?,?,?,'EXECUTE_ACTION','PENDING',?,?,'MVP-02:1',?,?,0)
                """,id,planId,command.actionId(),actor,Timestamp.from(time),Timestamp.from(time.plusSeconds(command.validForSeconds())),
                Timestamp.from(time),plan.fingerprint());
        store.jdbc.update("UPDATE intervention_plan SET status='AWAITING_AUTHORIZATION',revision=revision+1 WHERE plan_id=?",planId);
        store.change(cycle,"E05_ESPERANDO_AUTORIZACION",EventType.AUTHORIZATION_REQUESTED,actor,"Solicitud de autorización de acción","authorization:"+id);
        return authorizations.get(id);
    }

    /** Consulta la solicitud sin modificar su estado o renovar su vigencia. */
    @Transactional(readOnly=true)
    public Authorization getAuthorization(UUID id) { return authorizations.get(id); }

    /**
     * Registra una decisión humana única. Solo activa permisos cuando todas las
     * acciones tienen aprobación vigente del mismo contenido; un rechazo suspende
     * el ciclo e invalida solicitudes relacionadas sin borrar sus decisiones.
     */
    @Transactional(timeout=10)
    public Authorization decide(UUID id,Decide command,boolean approve,String actor) {
        store.lifecycle.validate(command); actor=LifecycleService.actor(actor);
        var initial=authorizations.get(id);
        var locked=plans.lock(initial.planId()); var plan=locked.plan(); var cycle=locked.cycle();
        var rows=store.jdbc.query("SELECT authorization_id FROM authorizations WHERE authorization_id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),id);
        if(rows.isEmpty()) throw fail(NOT_FOUND,"AUTHORIZATION_NOT_FOUND","La autorización no existe");
        var current=authorizations.get(id);
        if(current.version()!=command.expectedVersion()) throw fail(CONFLICT,"AUTHORIZATION_VERSION_CONFLICT","La versión de la solicitud cambió");
        if(!plan.planId().equals(current.planId())||!"EXECUTE_ACTION".equals(current.scope())
                ||plan.actions().stream().noneMatch(action->action.actionId().equals(current.actionId()))) {
            throw fail(CONFLICT,"AUTHORIZATION_CHANGED","La solicitud no corresponde al plan");
        }
        if(!"PENDING".equals(current.status())||current.decision()!=null
                ||!"AWAITING_AUTHORIZATION".equals(plan.status())||!"E05_ESPERANDO_AUTORIZACION".equals(cycle.state())) {
            throw fail(CONFLICT,"AUTHORIZATION_NOT_PENDING","La solicitud ya no admite una decisión");
        }
        if(actor.equalsIgnoreCase(current.requestedBy())) throw fail(FORBIDDEN,"SELF_APPROVAL_FORBIDDEN","Solicitante y aprobador deben usar identidades distintas");
        String snapshot=store.jdbc.queryForObject("SELECT request_fingerprint FROM authorizations WHERE authorization_id=?",String.class,id);
        if(!plan.fingerprint().equals(snapshot)) throw fail(CONFLICT,"PLAN_CHANGED","Cambió el contenido del plan solicitado");
        var time=store.lifecycle.now();
        if(approve&&(current.issuedAt().isAfter(time)||current.expiresAt()==null||!current.expiresAt().isAfter(time)
                ||!current.expiresAt().isAfter(current.issuedAt()))) {
            throw fail(CONFLICT,"AUTHORIZATION_EXPIRED","La solicitud no tiene una vigencia válida para aprobar");
        }
        store.jdbc.update("UPDATE authorizations SET status=?,approved_by=?,version=version+1 WHERE authorization_id=?",
                approve?"APPROVED_PENDING_PLAN":"REJECTED",approve?actor:null,id);
        String target=cycle.state();
        if(approve) {
            if(ready(plan,time)) {
                store.jdbc.update("UPDATE authorizations SET status='APPROVED',version=version+1 WHERE plan_id=? AND status='APPROVED_PENDING_PLAN'",plan.planId());
                store.jdbc.update("UPDATE intervention_plan SET status='APPROVED',revision=revision+1 WHERE plan_id=?",plan.planId());
                target="E06_PREPARADO";
            } else store.jdbc.update("UPDATE intervention_plan SET revision=revision+1 WHERE plan_id=?",plan.planId());
        } else {
            store.jdbc.update("""
                    UPDATE authorizations SET status=CASE WHEN status='APPROVED' THEN 'REVOKED' ELSE 'CANCELLED' END,
                        revoked_at=CASE WHEN status='APPROVED' THEN ? ELSE revoked_at END,version=version+1
                    WHERE plan_id=? AND authorization_id<>? AND status IN ('PENDING','APPROVED_PENDING_PLAN','APPROVED')
                    """,Timestamp.from(time),plan.planId(),id);
            store.jdbc.update("UPDATE intervention_plan SET status='REJECTED',revision=revision+1 WHERE plan_id=?",plan.planId());
            target="E12_SUSPENDIDO";
        }
        UUID event=store.change(cycle,target,approve?EventType.APPROVAL_GRANTED:EventType.APPROVAL_REJECTED,
                actor,command.reason().trim(),"authorization:"+id);
        store.jdbc.update("INSERT INTO authorization_decision VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID(),id,
                approve?"APPROVED":"REJECTED",actor,command.reason().trim(),Timestamp.from(time),snapshot,event);
        return authorizations.get(id);
    }

    /** Evalúa una activación propuesta con IR-010; nada se vuelve utilizable antes de confirmar todo el plan. */
    private boolean ready(Plan plan,java.time.Instant time) {
        var candidates=new ArrayList<Authorization>();
        for(var action:plan.actions()) {
            var ids=store.jdbc.query("SELECT authorization_id FROM authorizations WHERE plan_id=? AND action_id=? AND status='APPROVED_PENDING_PLAN'",
                    (rs,row)->rs.getObject(1,UUID.class),plan.planId(),action.actionId());
            if(ids.isEmpty()) return false;
            if(ids.size()!=1) throw fail(CONFLICT,"AMBIGUOUS_AUTHORIZATION","La acción tiene más de una aprobación candidata");
            candidates.add(authorizations.get(ids.getFirst()));
        }
        for(var candidate:candidates) {
            String fingerprint=store.jdbc.queryForObject("SELECT request_fingerprint FROM authorizations WHERE authorization_id=?",String.class,candidate.authorizationId());
            var projected=new AuthorizationSnapshot(candidate.authorizationId(),candidate.planId(),candidate.actionId(),candidate.scope(),
                    "APPROVED",candidate.approvedBy(),candidate.issuedAt(),candidate.expiresAt(),candidate.revokedAt());
            if(!plan.fingerprint().equals(fingerprint)||!validity.evaluate(projected,new AuthorizationUseRequest(plan.planId(),candidate.actionId(),"EXECUTE_ACTION",time)).allowed()) {
                throw fail(CONFLICT,"AUTHORIZATION_WINDOW_INVALID","Una aprobación del plan cambió o dejó de estar vigente");
            }
        }
        return true;
    }
}
