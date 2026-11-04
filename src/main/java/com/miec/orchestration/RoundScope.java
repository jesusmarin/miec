package com.miec.orchestration;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Identidad de ronda compartida por medición, planificación y cierre, sin dependencias a sus workflows. */
@Component
class RoundScope {
    private final InterventionSupport store;
    private final JsonMapper json;
    /** Comparte persistencia y serialización del servidor. */
    RoundScope(InterventionSupport store,JsonMapper json){this.store=store;this.json=json;}
    /** Ronda actual o última concluida; null conserva el contrato histórico sin ronda explícita. */
    UUID current(UUID cycle){return store.jdbc.query("SELECT round_id FROM cycle_round WHERE cycle_id=? ORDER BY round_number DESC LIMIT 1",
        (rs,row)->rs.getObject(1,UUID.class),cycle).stream().findFirst().orElse(null);}
    /** Rechaza referencias de otro ciclo sin reinterpretar registros legados. */
    void requireRound(UUID cycle,UUID round){if(round==null||store.jdbc.queryForObject("SELECT COUNT(*) FROM cycle_round WHERE cycle_id=? AND round_id=?",Integer.class,cycle,round)!=1)
        throw fail(NOT_FOUND,"ROUND_NOT_FOUND","La ronda no pertenece al ciclo");}
    /** Identifica el contexto histórico exacto de un plan. */
    UUID forPlan(UUID plan){return store.jdbc.query("SELECT round_id FROM round_plan_binding WHERE plan_id=?",(rs,row)->rs.getObject(1,UUID.class),plan).stream().findFirst().orElse(null);}
    /** Exige el plan de la ronda actual para comandos; las consultas históricas no usan esta restricción. */
    void requirePlan(UUID cycle,UUID plan){var round=current(cycle);if(round!=null&&!round.equals(forPlan(plan)))
        throw fail(CONFLICT,"PLAN_ROUND_MISMATCH","El plan no pertenece a la ronda actual");}
    /** Asigna una muestra recién registrada; nunca reutiliza una muestra de otra ronda. */
    void baseline(UUID cycle,UUID baseline,UUID indicator){var round=current(cycle);if(round!=null)
        store.jdbc.update("INSERT INTO round_baseline VALUES (?,?,?,?)",baseline,round,cycle,indicator);}
    /** Vincula el cierre de observación o reporte manual recién creado. */
    void observation(UUID cycle,UUID evidence){var round=current(cycle);if(round!=null)
        store.jdbc.update("INSERT INTO round_observation VALUES (?,?,?)",evidence,round,cycle);}
    /** Exige evidencia de observación de la ronda activa antes de diagnosticar. */
    void requireObservation(UUID cycle,UUID evidence){var round=current(cycle);if(round!=null&&store.jdbc.queryForObject(
        "SELECT COUNT(*) FROM round_observation WHERE round_id=? AND evidence_id=?",Integer.class,round,evidence)!=1)
        throw fail(BAD_REQUEST,"OBSERVATION_ROUND_MISMATCH","La observación pertenece a otra ronda");}
    /** Una nueva ronda no puede presentar como nueva una observación anterior a la intervención precedente. */
    void requireNewObservation(UUID cycle,java.time.Instant observed){
        var snapshots=store.jdbc.query("""
            SELECT c.context_snapshot FROM cycle_round r JOIN round_context c ON c.round_id=r.previous_round_id
            WHERE r.round_id=?
            """,(rs,row)->json.readValue(rs.getString(1),RoundContracts.Snapshot.class),current(cycle));
        if(!snapshots.isEmpty()&&(observed.getNano()%1000!=0||observed.isBefore(snapshots.getFirst().evaluation().inputs().finalizedAt())))
            throw fail(BAD_REQUEST,"BASELINE_PRECEDES_PREVIOUS_EXECUTION","La observación debe ser posterior a la intervención anterior y tener precisión de microsegundos");
    }
    /** Conserva el diagnóstico dentro de su ronda. */
    void diagnosis(UUID cycle,UUID diagnosis){var round=current(cycle);if(round!=null)
        store.jdbc.update("INSERT INTO round_diagnosis VALUES (?,?,?)",diagnosis,round,cycle);}
    /** Reserva un solo plan por ronda, preservando los planes anteriores. */
    void bind(UUID cycle,UUID plan){var round=current(cycle);if(round!=null)
        store.jdbc.update("INSERT INTO round_plan_binding VALUES (?,?,?)",round,cycle,plan);}
    /** Congela el contexto al terminar aprendizaje; no sustituye una instantánea previa. */
    void capture(UUID round,InterventionViews.Plan plan,EvaluationContracts.Saved saved,CycleCompletionContracts.View view){
        var input=saved.inputs();var snapshot=new RoundContracts.Snapshot("ROUND-CONTEXT-V1",saved,view.decision(),view.learning());
        store.jdbc.update(store.sql.json("INSERT INTO round_context VALUES (?,?,?,?,?,?,?,?,?,? FORMAT JSON)"),round,input.cycleId(),plan.planId(),input.runId(),input.setId(),
            view.admission().evaluationId(),view.decision().decisionId(),view.learning().learningId(),plan.fingerprint(),json.writeValueAsString(snapshot));
    }
    /** Enlaza solamente las muestras y el diagnóstico acreditados por el contexto inicial. */
    void initialInputs(UUID round,InterventionViews.Plan plan,EvaluationContracts.Saved saved){
        store.jdbc.update("INSERT INTO round_plan_binding VALUES (?,?,?)",round,plan.cycleId(),plan.planId());
        admit(round,plan,saved);
        store.jdbc.update("UPDATE evaluation_admission SET round_id=?,legacy_cycle_id=NULL WHERE evaluation_id=? AND round_id IS NULL",round,saved.report().result().id());
        store.jdbc.update("UPDATE human_decision SET round_id=?,legacy_cycle_id=NULL WHERE evaluation_id=? AND round_id IS NULL",round,saved.report().result().id());
        for(var pair:saved.inputs().pairs())store.jdbc.update("INSERT INTO round_baseline VALUES (?,?,?,?)",pair.baseline().id(),round,plan.cycleId(),pair.indicatorId());
        store.jdbc.update("INSERT INTO round_diagnosis VALUES (?,?,?)",plan.diagnosisId(),round,plan.cycleId());
        store.jdbc.update("INSERT INTO round_observation SELECT evidence_id,?,cycle_id FROM cycle_diagnosis WHERE diagnosis_id=?",round,plan.diagnosisId());
    }
    /** Una admisión por ronda, con el mismo plan y ciclo del conjunto evaluado. */
    void admit(UUID round,InterventionViews.Plan plan,EvaluationContracts.Saved saved){if(round!=null)
        store.jdbc.update("INSERT INTO round_evaluation_binding VALUES (?,?,?,?)",round,plan.cycleId(),plan.planId(),saved.report().result().id());}
}
