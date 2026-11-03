package com.miec.orchestration;

import com.miec.decision.*;
import com.miec.domain.Cycle;
import com.miec.state.CycleState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.util.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Registra la recomendación F02 inmutable; no decide por el humano ni cambia estado o permisos. */
@Service
class JdbcDecisionRecorder implements DecisionRecorder {
    private final InterventionSupport store;
    private final ClosedSetEvaluationStore evaluations;
    private final tools.jackson.databind.json.JsonMapper json;
    /** Usa la misma transacción que coordinará decisión humana, evento y transición. */
    JdbcDecisionRecorder(InterventionSupport store,ClosedSetEvaluationStore evaluations,tools.jackson.databind.json.JsonMapper json){this.store=store;this.evaluations=evaluations;this.json=json;}
    /** Conserva una propuesta completa sobre una evaluación persistida, sin efectos de aplicación. */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public UUID record(DecisionOutcome outcome) {
        Objects.requireNonNull(outcome);
        if(!evaluations.get(outcome.evaluation().id()).report().result().equals(outcome.evaluation()))throw fail(CONFLICT,"DECISION_EVALUATION_MISMATCH","La recomendación no corresponde a la evaluación guardada");
        var transition=outcome.transitionProposal();
        if(transition.sourceState()!=CycleState.E09_DECIDIENDO||!Cycle.isValidTransition(transition.sourceState(),transition.proposedState()))
            throw fail(CONFLICT,"DECISION_TRANSITION_INVALID","La transición inmediata no cumple F01");
        UUID id=UUID.randomUUID();
        store.jdbc.update("""
                INSERT INTO decision_record(decision_id,cycle_id,evaluation_id,decision_type,recommendation,alternatives_considered,selection_rationale,
                    authorization_status,proposed_transition,decision_status,decision_rule_version_id,created_at,outcome_snapshot)
                VALUES (?,?,?,'MVP_RECOMMENDATION',?,? FORMAT JSON,?,'NOT_GRANTED',? FORMAT JSON,'RECORDED',?,?,? FORMAT JSON)
                """,id,outcome.evaluation().cycleId(),outcome.evaluation().id(),outcome.recommendation().action().name(),json.writeValueAsString(outcome.alternatives()),
                outcome.recommendation().rationale(),json.writeValueAsString(transition),MvpAlternativePolicy.VERSION,Timestamp.from(outcome.recommendation().recommendedAt()),json.writeValueAsString(outcome));
        return id;
    }
    /** Recupera la propuesta original; ausencia devuelve null conforme al contrato DecisionRecorder. */
    @Override @Transactional(readOnly=true)
    public DecisionOutcome retrieve(UUID id) {
        Objects.requireNonNull(id);var values=store.jdbc.query("SELECT outcome_snapshot FROM decision_record WHERE decision_id=?",(rs,row)->rs.getString(1),id);
        if(values.isEmpty())return null;
        if(values.getFirst()==null)throw fail(CONFLICT,"DECISION_SNAPSHOT_UNAVAILABLE","La decisión histórica no conserva snapshot F02-MVP");
        return json.readValue(values.getFirst(),DecisionOutcome.class);
    }
}
