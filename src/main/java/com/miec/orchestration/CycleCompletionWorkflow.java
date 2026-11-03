package com.miec.orchestration;

import com.miec.action.ExecutionOutcomeQueryService;
import com.miec.domain.EventType;
import com.miec.decision.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.miec.orchestration.CycleCompletionContracts.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Transacciones de cierre con F01, versiones y proyección IR-009; nunca efectúa IO externo. */
@Service
class CycleCompletionWorkflow {
    private final InterventionSupport store;
    private final InterventionPlans plans;
    private final ClosedSetEvaluationStore evaluations;
    private final PlanExecutionStore runs;
    private final ExecutionOutcomeQueryService outcomes;
    private final DefaultDecisionEngine engine;
    private final JdbcDecisionRecorder recorder;
    /** Comparte bloqueos y persistencia de evaluación con los incrementos anteriores. */
    CycleCompletionWorkflow(InterventionSupport store,InterventionPlans plans,ClosedSetEvaluationStore evaluations,
                            PlanExecutionStore runs,ExecutionOutcomeQueryService outcomes,DefaultDecisionEngine engine,JdbcDecisionRecorder recorder) {
        this.store=store;this.plans=plans;this.evaluations=evaluations;this.runs=runs;this.outcomes=outcomes;
        this.engine=engine;this.recorder=recorder;
    }
    /** Admite conclusión y avanza a E09 en la misma transacción; repetición con revisión vieja falla. */
    @Transactional(timeout=10)
    public EvaluationCreated evaluate(UUID cycleId,UUID planId,Evaluate command,String actor) {
        store.lifecycle.validate(command);var locked=plans.lock(planId);
        if(!cycleId.equals(locked.cycle().cycleId()))throw fail(BAD_REQUEST,"PLAN_CYCLE_MISMATCH","El plan no pertenece al ciclo");
        store.expect(locked.cycle(),command.expectedVersion(),"E08_VERIFICANDO");
        requireHealthy(planId,cycleId);
        var saved=evaluations.persist(planId,command.setId(),command.expectedVersion(),actor);
        UUID event=store.change(locked.cycle(),"E09_DECIDIENDO",EventType.EVALUATION_RECORDED,actor,command.reason().trim(),"evaluation:"+saved.report().result().id());
        var at=eventTime(event);
        store.jdbc.update("INSERT INTO evaluation_admission VALUES (?,?,?,?,?,?,?)",saved.report().result().id(),cycleId,event,command.expectedVersion(),actor,command.reason().trim(),stamp(at));
        return new EvaluationCreated(saved,view(cycleId));
    }
    /** Consulta el recibo operativo conservando ausencia de pasos todavía no realizados. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View view(UUID cycleId) {
        var cycle=store.lifecycle.getCycle(cycleId);var admission=admission(cycleId);
        return new View(cycle,admission,human(cycleId),learning(cycleId),closure(cycleId),admission!=null&&!technicalClear(evaluations.get(admission.evaluationId()).inputs().planId(),cycleId));
    }
    /** Vista previa sin autoridad ni cambios; al decidir se conserva una nueva propuesta con fecha de base. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DecisionOutcome preview(UUID evaluationId) {
        var saved=evaluations.get(evaluationId);var receipt=admission(saved.inputs().cycleId());
        if(receipt==null||!receipt.evaluationId().equals(evaluationId))throw fail(CONFLICT,"EVALUATION_NOT_ADMITTED","La evaluación no tiene admisión operativa");
        if(!"E09_DECIDIENDO".equals(store.lifecycle.getCycle(saved.inputs().cycleId()).state()))throw fail(CONFLICT,"DECISION_NOT_PENDING","La etapa no admite una nueva recomendación");
        return engine.decide(saved.report().result());
    }
    /** Selecciona una intención humana admisible y registra E09 a E10 con auditoría atómica. */
    @Transactional(timeout=10)
    public View decide(UUID cycleId,Decide command,String actor) {
        store.lifecycle.validate(command);var saved=evaluations.get(command.evaluationId());
        if(!cycleId.equals(saved.inputs().cycleId()))throw fail(BAD_REQUEST,"EVALUATION_CYCLE_MISMATCH","La evaluación no pertenece al ciclo");
        var locked=plans.lock(saved.inputs().planId());store.expect(locked.cycle(),command.expectedVersion(),"E09_DECIDIENDO");
        var receipt=admission(cycleId);
        if(receipt==null||!receipt.evaluationId().equals(command.evaluationId()))throw fail(CONFLICT,"EVALUATION_NOT_ADMITTED","La evaluación no tiene admisión operativa");
        if(actor.equalsIgnoreCase(receipt.recordedBy())||actor.equalsIgnoreCase(locked.plan().createdBy()))throw fail(FORBIDDEN,"SELF_DECISION_FORBIDDEN","Autor y decisor deben ser identidades distintas");
        requireHealthy(saved.inputs().planId(),cycleId);
        var outcome=engine.decide(saved.report().result(),store.lifecycle.now());
        if(outcome.alternatives().stream().noneMatch(a->a.action()==command.action()&&a.admissible()))throw fail(CONFLICT,"DECISION_ACTION_INADMISSIBLE","La intención no es admisible bajo la política instalada");
        UUID id=recorder.record(outcome);
        UUID event=store.change(locked.cycle(),"E10_APRENDIENDO",EventType.DECISION_RECORDED,actor,command.reason().trim(),"decision:"+id);
        store.jdbc.update("INSERT INTO human_decision VALUES (?,?,?,?,?,?,TRUE,?,?,?)",id,command.evaluationId(),cycleId,command.action().name(),actor,command.reason().trim(),MvpAlternativePolicy.VERSION,event,stamp(eventTime(event)));
        return view(cycleId);
    }
    /** Aprendizaje explícito con confianza y evidencias del evaluador, nunca niveles elegidos por el cliente. */
    @Transactional(timeout=10)
    public View learn(UUID cycleId,Learn command,String actor) {
        store.lifecycle.validate(command);var context=lockChoice(cycleId,command.decisionId(),command.expectedVersion(),"E10_APRENDIENDO");
        UUID id=UUID.randomUUID();UUID event=store.change(context.locked().cycle(),"E11_CONTINUANDO",EventType.LEARNING_RECORDED,actor,command.reason().trim(),"learning:"+id);
        var evaluation=context.evaluation().report().result();
        store.jdbc.update("""
                INSERT INTO learning(learning_id,cycle_id,statement,supporting_evidence_ids,confidence_level,applicable_conditions,status,created_at,decision_id,recorded_by,limitations,event_id)
                VALUES (?,?,?,? FORMAT JSON,?,?,'RECORDED',?,?,?,?,?)
                """,id,cycleId,command.statement().trim(),plans.json.writeValueAsString(evaluation.evidenceAssessment().evidenceIds()),evaluation.confidenceLevel().name(),
                command.conditions().trim(),stamp(eventTime(event)),command.decisionId(),actor,command.limitations().trim(),event);
        return view(cycleId);
    }
    /** Cierre administrativo explícito: estado, closed_at, evento, acta y versión son una sola confirmación. */
    @Transactional(timeout=10)
    public View close(UUID cycleId,Close command,String actor) {
        store.lifecycle.validate(command);var learning=learning(cycleId);
        if(learning==null||!learning.learningId().equals(command.learningId()))throw fail(CONFLICT,"LEARNING_REQUIRED","Se requiere el aprendizaje operativo de este ciclo");
        var context=lockChoice(cycleId,learning.decisionId(),command.expectedVersion(),"E11_CONTINUANDO");
        if(context.choice().selectedAction()!=DecisionAction.CLOSE_CYCLE)throw fail(CONFLICT,"CLOSURE_NOT_SELECTED","La decisión humana no seleccionó cierre; la continuidad requiere su contrato operativo");
        UUID event=store.change(context.locked().cycle(),"E14_FINALIZADO",EventType.CYCLE_FINISHED,actor,command.reason().trim(),"cycle-closure:"+cycleId);
        var at=eventTime(event);
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM cycle_round WHERE cycle_id=? AND round_state='ACTIVE'",Integer.class,cycleId)>0) {
            if(store.jdbc.queryForObject("SELECT COUNT(*) FROM plan_completion WHERE cycle_id=?",Integer.class,cycleId)!=1)
                throw fail(CONFLICT,"PLAN_COMPLETION_REQUIRED","La ronda explícita requiere concluir su plan antes del cierre");
            store.jdbc.update("UPDATE cycle_round SET round_state='CONCLUDED',active_cycle_id=NULL,concluded_at=?,version=version+1 WHERE cycle_id=? AND round_state='ACTIVE'",stamp(at),cycleId);
        }
        if(store.jdbc.update("UPDATE cycle SET closed_at=?,closure_reason=? WHERE cycle_id=? AND state='E14_FINALIZADO' AND closed_at IS NULL",stamp(at),command.reason().trim(),cycleId)!=1)
            throw fail(CONFLICT,"CYCLE_ALREADY_CLOSED","El ciclo ya tiene cierre");
        store.jdbc.update("INSERT INTO cycle_closure VALUES (?,?,?,?,?,?,TRUE,?,?)",cycleId,learning.decisionId(),learning.learningId(),actor,command.reason().trim(),command.summary().trim(),event,stamp(at));
        return view(cycleId);
    }
    /** Recupera el acta confirmada, sin inferir éxito de la existencia de closed_at. */
    private Closure closure(UUID cycleId) {
        return store.jdbc.query("SELECT * FROM cycle_closure WHERE cycle_id=?",(rs,row)->new Closure(rs.getObject("learning_id",UUID.class),rs.getString("summary"),
                rs.getString("reason"),rs.getString("closed_by"),rs.getTimestamp("closed_at").toInstant()),cycleId).stream().findFirst().orElse(null);
    }
    /** Contexto bloqueado que relaciona elección humana con su conclusión original. */
    private record Chosen(InterventionPlans.Locked locked,HumanDecision choice,EvaluationContracts.Saved evaluation) { }
    /** Revalida identidad, versión, etapa y resultados técnicos sin reescribir la decisión. */
    private Chosen lockChoice(UUID cycleId,UUID decisionId,long version,String state) {
        var choice=human(cycleId);
        if(choice==null||!choice.decisionId().equals(decisionId))throw fail(CONFLICT,"DECISION_REQUIRED","Se requiere la decisión operativa de este ciclo");
        var saved=evaluations.get(choice.evaluationId());var locked=plans.lock(saved.inputs().planId());
        if(!cycleId.equals(locked.cycle().cycleId()))throw fail(BAD_REQUEST,"DECISION_CYCLE_MISMATCH","La decisión no pertenece al ciclo");
        store.expect(locked.cycle(),version,state);requireHealthy(saved.inputs().planId(),cycleId);
        return new Chosen(locked,choice,saved);
    }
    /** Recupera aprendizaje operativo sin reinterpretar registros históricos anteriores a V017. */
    private Learning learning(UUID cycleId) {
        return store.jdbc.query("SELECT * FROM learning WHERE cycle_id=? AND decision_id IS NOT NULL",(rs,row)->new Learning(rs.getObject("learning_id",UUID.class),rs.getObject("decision_id",UUID.class),
                rs.getString("statement"),rs.getString("applicable_conditions"),rs.getString("limitations"),rs.getString("recorded_by"),ConfidenceLevel.valueOf(rs.getString("confidence_level")),
                rs.getTimestamp("created_at").toInstant()),cycleId).stream().findFirst().orElse(null);
    }
    /** Reconstruye decisión y recomendación originales sin recalcular políticas al consultar. */
    private HumanDecision human(UUID cycleId) {
        return store.jdbc.query("SELECT * FROM human_decision WHERE cycle_id=?",(rs,row)->new HumanDecision(rs.getObject("decision_id",UUID.class),rs.getObject("evaluation_id",UUID.class),
                DecisionAction.valueOf(rs.getString("selected_action")),rs.getString("decided_by"),rs.getString("reason"),rs.getString("policy_version"),rs.getTimestamp("decided_at").toInstant(),
                recorder.retrieve(rs.getObject("decision_id",UUID.class))),cycleId).stream().findFirst().orElse(null);
    }
    /** Cierra la ventana de nuevas escrituras IR-009 de todos los planes del ciclo antes de comprobar incertidumbre. */
    void requireHealthy(UUID planId,UUID cycleId) {
        store.jdbc.query("SELECT plan_id FROM intervention_plan WHERE cycle_id=? ORDER BY plan_id FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),cycleId);
        store.jdbc.query("SELECT run_id FROM plan_execution WHERE plan_id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),planId);
        if(!technicalClear(planId,cycleId))throw fail(CONFLICT,"EXECUTION_UNCERTAIN","Hay resultados técnicos pendientes o contradictorios en el ciclo");
    }
    /** Recibo único del ciclo; no fabrica admisiones para conclusiones internas históricas. */
    private Admission admission(UUID cycleId) {
        return store.jdbc.query("SELECT * FROM evaluation_admission WHERE cycle_id=?",(rs,row)->new Admission(rs.getObject("evaluation_id",UUID.class),
                rs.getObject("event_id",UUID.class),rs.getString("recorded_by"),rs.getString("reason"),rs.getTimestamp("recorded_at").toInstant()),cycleId).stream().findFirst().orElse(null);
    }
    /** Todas las incertidumbres históricas deben estar resueltas, no solo el último intento de cada acción. */
    private boolean technicalClear(UUID planId,UUID cycleId) {
        var run=runs.view(planId);
        if(!"APPLIED".equals(run.state())||run.requiresReview()||run.steps().isEmpty()
                ||run.steps().stream().anyMatch(s->!"APPLIED".equals(s.observedOutcome())))return false;
        var attempts=store.jdbc.query("SELECT execution_id FROM execution_attempt WHERE cycle_id=?",(rs,row)->rs.getObject(1,UUID.class),cycleId);
        return attempts.stream().allMatch(id->outcomes.findAttempt(id).map(f->List.of("APPLIED","NO_EFFECT","CANCELLED_PENDING").contains(f.outcome().name())).orElse(false));
    }
    /** Hace coincidir las fechas de recibos con el evento de auditoría confirmado. */
    private Instant eventTime(UUID id){return store.jdbc.queryForObject("SELECT occurred_at FROM cycle_event WHERE event_id=?",(rs,row)->rs.getTimestamp(1).toInstant(),id);}
    /** Conversión sin usar un reloj de aplicación adicional. */
    private static Timestamp stamp(Instant at){return Timestamp.from(at);}
}
