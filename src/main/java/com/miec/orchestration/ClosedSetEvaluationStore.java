package com.miec.orchestration;

import com.miec.decision.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.util.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/**
 * Persistencia interna append-only. No publica creación ni mueve el ciclo; MANDATORY
 * exige la transacción que 06.4 coordinará con evento, transición y versión.
 */
@Service
class ClosedSetEvaluationStore {
    private final PlanActivityQuery activity;
    private final InterventionSupport store;
    private final InterventionPlans plans;
    private final PlanExecutionStore runs;
    private final MeasurementQueries queries;
    private final EvaluationSnapshotLoader loader;
    private final DeterministicResultEvaluator evaluator;
    private final tools.jackson.databind.json.JsonMapper json;
    /** Configura persistencia y servicios existentes sin llamadas remotas. */
    ClosedSetEvaluationStore(InterventionSupport store,InterventionPlans plans,PlanExecutionStore runs,MeasurementQueries queries,
                             EvaluationSnapshotLoader loader,DeterministicResultEvaluator evaluator,tools.jackson.databind.json.JsonMapper json,PlanActivityQuery activity) {
        this.activity=activity;
        this.store=store;this.plans=plans;this.runs=runs;this.queries=queries;this.loader=loader;this.evaluator=evaluator;this.json=json;
    }
    /** Revalida contexto y guarda una conclusión por conjunto/políticas; una repetición devuelve la original. */
    @Transactional(propagation=Propagation.MANDATORY,timeout=10)
    public EvaluationContracts.Saved persist(UUID planId,UUID setId,long expectedCycleVersion,String actor) {
        actor=LifecycleService.actor(actor);var locked=plans.lock(planId);store.expect(locked.cycle(),expectedCycleVersion,"E08_VERIFICANDO");
        activity.requireOpen(planId);
        var ids=store.jdbc.query("SELECT run_id FROM plan_execution WHERE plan_id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),planId);
        if(ids.size()!=1)throw fail(CONFLICT,"EXECUTION_NOT_CONFIRMED","El plan no tiene ejecución confirmada");
        var run=runs.view(planId);
        if(!"APPROVED".equals(locked.plan().status())||!"APPLIED".equals(run.state())||run.requiresReview()||run.steps().isEmpty()
                ||run.steps().stream().anyMatch(s->!"APPLIED".equals(s.state())||!"APPLIED".equals(s.observedOutcome())))
            throw fail(CONFLICT,"EXECUTION_NOT_CONFIRMED","La ejecución requiere revisión");
        var context=queries.posts(locked.cycle().cycleId(),planId);
        if(context.set()==null||!context.set().setId().equals(setId)||!context.set().runId().equals(run.runId()))
            throw fail(CONFLICT,"EVALUATION_SET_MISMATCH","Se requiere el conjunto cerrado de esta ejecución");
        var dates=store.jdbc.query("SELECT occurred_at FROM cycle_event WHERE cycle_id=? AND event_type='PLAN_EXECUTION_FINALIZED' AND payload_reference=?",
                (rs,row)->rs.getTimestamp(1).toInstant(),locked.cycle().cycleId(),"plan-execution:"+run.runId());
        if(dates.size()!=1)throw fail(CONFLICT,"EXECUTION_NOT_FINALIZED","Se requiere finalización técnica única");
        final ClosedSetEvaluation.Snapshot snapshot;
        final ClosedSetEvaluation.Report report;
        try {
            snapshot=loader.load(locked.plan(),run,context,dates.getFirst());
            report=evaluator.report(evaluator.request(snapshot,store.lifecycle.now()));
        }catch(IllegalArgumentException invalid){
            throw fail(CONFLICT,"EVALUATION_INPUT_INVALID","Las entradas no cumplen las políticas de evaluación");
        }
        var previous=store.jdbc.query("SELECT evaluation_id FROM evaluation WHERE measurement_set_id=? AND evaluation_policy_version=? AND evidence_policy_version=?",
                (rs,row)->rs.getObject(1,UUID.class),setId,ClosedSetEvaluation.POLICY,ClosedSetEvaluation.EVIDENCE_POLICY);
        if(!previous.isEmpty()) {
            var old=get(previous.getFirst());
            if(!old.report().inputFingerprint().equals(report.inputFingerprint()))throw fail(CONFLICT,"EVALUATION_INPUT_CHANGED","Los datos difieren de la conclusión guardada");
            return old;
        }
        var saved=new EvaluationContracts.Saved("EVALUATION-SNAPSHOT-01",actor,snapshot,report);var result=report.result();
        store.jdbc.update(store.sql.json("""
                INSERT INTO evaluation(evaluation_id,cycle_id,objective_id,criteria_version_id,observed_results,result_classification,evidence_sufficiency,
                    confidence_level,limitations,rationale,evaluated_at,evaluator_reference,created_at,measurement_set_id,run_id,plan_id,
                    evaluation_policy_version,evidence_policy_version,input_fingerprint,evaluation_snapshot,recorded_by)
                VALUES (?,?,?,?,? FORMAT JSON,?,?,?, ?,?,?,?, ?,?,?,?,?,?,?,? FORMAT JSON,?)
                """),result.id(),result.cycleId(),result.objectiveId(),ClosedSetEvaluation.criteriaVersion(snapshot),json.writeValueAsString(report.comparisons()),result.classification().name(),
                result.evidenceAssessment().sufficiency().name(),result.confidenceLevel().name(),json.writeValueAsString(result.evidenceAssessment().limitations()),result.rationale(),
                Timestamp.from(result.evaluatedAt()),ClosedSetEvaluation.POLICY,Timestamp.from(result.evaluatedAt()),setId,run.runId(),planId,report.policyVersion(),report.evidencePolicyVersion(),
                report.inputFingerprint(),json.writeValueAsString(saved),actor);
        for(var proof:result.evidenceAssessment().evidenceIds())store.jdbc.update("INSERT INTO evaluation_evidence(evaluation_id,evidence_id) VALUES (?,?)",result.id(),proof);
        return get(result.id());
    }
    /** Lee exclusivamente el snapshot persistido; los registros históricos sin extensión no se reinterpretan. */
    @Transactional(readOnly=true)
    public EvaluationContracts.Saved get(UUID id) {
        var values=store.jdbc.query("SELECT evaluation_snapshot FROM evaluation WHERE evaluation_id=?",(rs,row)->rs.getString(1),id);
        if(values.isEmpty())throw fail(NOT_FOUND,"EVALUATION_NOT_FOUND","La evaluación no existe");
        if(values.getFirst()==null)throw fail(CONFLICT,"EVALUATION_SNAPSHOT_UNAVAILABLE","La evaluación histórica no conserva snapshot MVP-06");
        return json.readValue(values.getFirst(),EvaluationContracts.Saved.class);
    }
    /** Lista estable y acotada al conjunto; una selección nueva no sustituye una conclusión histórica. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<EvaluationContracts.Saved> forSet(UUID set) {
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM measurement_set WHERE set_id=?",Integer.class,set)==0)
            throw fail(NOT_FOUND,"MEASUREMENT_SET_NOT_FOUND","El conjunto no existe");
        return store.jdbc.query("SELECT evaluation_id FROM evaluation WHERE measurement_set_id=? ORDER BY evaluated_at,evaluation_id",(rs,row)->rs.getObject(1,UUID.class),set).stream().map(this::get).toList();
    }
}
