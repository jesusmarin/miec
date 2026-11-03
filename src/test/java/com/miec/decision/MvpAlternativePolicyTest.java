package com.miec.decision;

import com.miec.evidence.*;
import com.miec.domain.Cycle;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Admisibilidad explícita, prioridades y separación de estrategia respecto de F01. */
@DisplayName("F02-MVP-V1 — política productiva de alternativas")
class MvpAlternativePolicyTest {
    /** Resultado tipado que permite ensayar ramas futuras sin conceder EV alto a reportes humanos. */
    private EvaluationResult evaluation(ResultClassification classification) {
        var proof=new EvidenceAssessment(classification==ResultClassification.INCONCLUSIVE?EvidenceSufficiency.INSUFFICIENT:EvidenceSufficiency.SUFFICIENT,List.of(),List.of("limitación"),List.of(),"fixture",Instant.EPOCH);
        return new EvaluationResult(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),classification,proof,ConfidenceLevel.LOW,"fixture",Instant.EPOCH);
    }
    /** Cada clasificación produce una intención esperada y una transición inmediata válida. */
    @Test @DisplayName("Prioridad por clasificación y propuesta E09 a E10 válida")
    void shouldChooseExplicitPrioritiesAndValidTransitions() {
        var policy=new MvpAlternativePolicy();var engine=new DefaultDecisionEngine(policy,policy);
        for(var classification:ResultClassification.values()) {
            var result=engine.decide(evaluation(classification));
            var expected=switch(classification){case FAVORABLE->DecisionAction.CLOSE_CYCLE;case PARTIAL,UNFAVORABLE->DecisionAction.ADJUST_PLAN;case INCONCLUSIVE->DecisionAction.COLLECT_EVIDENCE;default->DecisionAction.REQUEST_HUMAN_REVIEW;};
            assertEquals(expected,result.recommendation().action());assertTrue(Cycle.isValidTransition(result.transitionProposal().sourceState(),result.transitionProposal().proposedState()));
        }
    }
    /** No declara éxito al permitir un cierre administrativo de RS-05; los efectos no soportados siguen inadmisibles. */
    @Test @DisplayName("Cierre administrativo no eleva RS-05 ni habilita efectos o ajustes sin evidencia")
    void shouldKeepAdministrativeClosureSeparateFromSuccess() {
        var values=new MvpAlternativePolicy().generateAlternatives(evaluation(ResultClassification.INCONCLUSIVE));
        assertTrue(values.stream().anyMatch(a->a.action()==DecisionAction.CLOSE_CYCLE&&a.admissible()));
        for(var action:List.of(DecisionAction.REVERT_CHANGE,DecisionAction.SUSPEND_CYCLE,DecisionAction.ABORT_INTERVENTION,DecisionAction.CONTINUE_PLAN,DecisionAction.ADJUST_PLAN))
            assertTrue(values.stream().anyMatch(a->a.action()==action&&!a.admissible()));
    }
}
