
package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;
import com.miec.evidence.EvidenceSufficiency;
import com.miec.state.CycleState;
import com.miec.state.TransitionProposal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DecisionOutcomeTest {

    private final Instant timestamp =
            Instant.parse("2026-10-30T10:00:00Z");

    private EvaluationResult createEvaluation() {
        EvidenceAssessment evidence = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(),
                "Evidence is sufficient",
                timestamp
        );

        return new EvaluationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ResultClassification.FAVORABLE,
                evidence,
                ConfidenceLevel.HIGH,
                "Evaluation completed",
                timestamp
        );
    }

    private AlternativeAssessment createAlternative(
            DecisionAction action,
            boolean admissible
    ) {
        return new AlternativeAssessment(
                action,
                admissible,
                "Assessment rationale",
                admissible ? "Expected improvement" : null,
                List.of(),
                "Low",
                true
        );
    }

    private DecisionRecommendation createRecommendation(
            DecisionAction action
    ) {
        return new DecisionRecommendation(
                action,
                "Recommended action rationale",
                List.of(UUID.randomUUID()),
                timestamp
        );
    }

    private TransitionProposal createTransitionProposal() {
        return new TransitionProposal(
                CycleState.E09_DECIDIENDO,
                CycleState.E10_APRENDIENDO,
                "Decision recorded",
                timestamp
        );
    }

    private DecisionOutcome createValidOutcome() {
        DecisionAction action = DecisionAction.CONTINUE_PLAN;

        return new DecisionOutcome(
                createEvaluation(),
                List.of(createAlternative(action, true)),
                createRecommendation(action),
                createTransitionProposal()
        );
    }

    @Test
    @DisplayName("Debe crear un resultado de decisión válido")
    void shouldCreateValidDecisionOutcome() {
        DecisionOutcome outcome = createValidOutcome();

        assertNotNull(outcome);
        assertNotNull(outcome.evaluation());
        assertNotNull(outcome.recommendation());
        assertNotNull(outcome.transitionProposal());
        assertEquals(1, outcome.alternatives().size());
    }

    @Test
    @DisplayName("Debe rechazar una evaluación nula")
    void shouldRejectNullEvaluation() {
        assertThrows(NullPointerException.class,
                () -> new DecisionOutcome(
                        null,
                        List.of(createAlternative(
                                DecisionAction.CONTINUE_PLAN, true)),
                        createRecommendation(DecisionAction.CONTINUE_PLAN),
                        createTransitionProposal()
                ));
    }

    @Test
    @DisplayName("Debe rechazar una lista de alternativas nula")
    void shouldRejectNullAlternatives() {
        assertThrows(NullPointerException.class,
                () -> new DecisionOutcome(
                        createEvaluation(),
                        null,
                        createRecommendation(DecisionAction.CONTINUE_PLAN),
                        createTransitionProposal()
                ));
    }

    @Test
    @DisplayName("Debe rechazar una lista de alternativas vacía")
    void shouldRejectEmptyAlternatives() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionOutcome(
                        createEvaluation(),
                        List.of(),
                        createRecommendation(DecisionAction.CONTINUE_PLAN),
                        createTransitionProposal()
                ));
    }

    @Test
    @DisplayName("Debe rechazar alternativas que contengan elementos nulos")
    void shouldRejectNullAlternativeElements() {
        List<AlternativeAssessment> alternatives =
                new ArrayList<>();

        alternatives.add(
                createAlternative(DecisionAction.CONTINUE_PLAN, true));
        alternatives.add(null);

        assertThrows(NullPointerException.class,
                () -> new DecisionOutcome(
                        createEvaluation(),
                        alternatives,
                        createRecommendation(DecisionAction.CONTINUE_PLAN),
                        createTransitionProposal()
                ));
    }

    @Test
    @DisplayName("Debe rechazar una recomendación nula")
    void shouldRejectNullRecommendation() {
        assertThrows(NullPointerException.class,
                () -> new DecisionOutcome(
                        createEvaluation(),
                        List.of(createAlternative(
                                DecisionAction.CONTINUE_PLAN, true)),
                        null,
                        createTransitionProposal()
                ));
    }

    @Test
    @DisplayName("Debe rechazar una propuesta de transición nula")
    void shouldRejectNullTransitionProposal() {
        assertThrows(NullPointerException.class,
                () -> new DecisionOutcome(
                        createEvaluation(),
                        List.of(createAlternative(
                                DecisionAction.CONTINUE_PLAN, true)),
                        createRecommendation(DecisionAction.CONTINUE_PLAN),
                        null
                ));
    }

    @Test
    @DisplayName("Debe rechazar una acción recomendada que no está entre las alternativas")
    void shouldRejectRecommendationNotAssessed() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionOutcome(
                        createEvaluation(),
                        List.of(createAlternative(
                                DecisionAction.CONTINUE_PLAN, true)),
                        createRecommendation(DecisionAction.ADJUST_PLAN),
                        createTransitionProposal()
                ));
    }

    @Test
    @DisplayName("Debe rechazar una acción recomendada que no es admisible")
    void shouldRejectInadmissibleRecommendation() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionOutcome(
                        createEvaluation(),
                        List.of(createAlternative(
                                DecisionAction.CONTINUE_PLAN, false)),
                        createRecommendation(DecisionAction.CONTINUE_PLAN),
                        createTransitionProposal()
                ));
    }

    @Test
    @DisplayName("Debe aceptar varias alternativas con acciones diferentes")
    void shouldAcceptDifferentAlternativeActions() {
        DecisionOutcome outcome = new DecisionOutcome(
                createEvaluation(),
                List.of(
                        createAlternative(
                                DecisionAction.CONTINUE_PLAN, true),
                        createAlternative(
                                DecisionAction.ADJUST_PLAN, true)
                ),
                createRecommendation(DecisionAction.CONTINUE_PLAN),
                createTransitionProposal()
        );

        assertEquals(2, outcome.alternatives().size());
    }

    @Test
    @DisplayName("Debe rechazar acciones alternativas duplicadas")
    void shouldRejectDuplicateAlternativeActions() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionOutcome(
                        createEvaluation(),
                        List.of(
                                createAlternative(
                                        DecisionAction.CONTINUE_PLAN, true),
                                createAlternative(
                                        DecisionAction.CONTINUE_PLAN, true)
                        ),
                        createRecommendation(DecisionAction.CONTINUE_PLAN),
                        createTransitionProposal()
                ));
    }

    @Test
    @DisplayName("Debe conservar una lista de alternativas inmutable")
    void shouldKeepAlternativesImmutable() {
        DecisionOutcome outcome = createValidOutcome();

        assertThrows(UnsupportedOperationException.class,
                () -> outcome.alternatives().add(
                        createAlternative(
                                DecisionAction.ADJUST_PLAN, true)));
    }

    @Test
    @DisplayName("Debe conservar las alternativas originales ante modificaciones externas")
    void shouldProtectAgainstExternalListModification() {
        List<AlternativeAssessment> alternatives =
                new ArrayList<>();

        alternatives.add(
                createAlternative(DecisionAction.CONTINUE_PLAN, true));

        DecisionOutcome outcome = new DecisionOutcome(
                createEvaluation(),
                alternatives,
                createRecommendation(DecisionAction.CONTINUE_PLAN),
                createTransitionProposal()
        );

        alternatives.clear();

        assertEquals(1, outcome.alternatives().size());
    }
}

