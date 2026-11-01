package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;
import com.miec.evidence.EvidenceSufficiency;
import com.miec.state.CycleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de la implementación concreta {@link DefaultDecisionEngine}.
 *
 * <p>Estas pruebas verifican que el motor orquesta correctamente los componentes
 * y produce un {@link DecisionOutcome} válido.
 */
@DisplayName("Pruebas de la implementación DefaultDecisionEngine MIEC")
class DefaultDecisionEngineTest {

    private AlternativeGenerator alternativeGenerator;
    private AlternativeEvaluator alternativeEvaluator;
    private DefaultDecisionEngine engine;

    @BeforeEach
    void setUp() {
        alternativeGenerator = new StubAlternativeGenerator();
        alternativeEvaluator = new StubAlternativeEvaluator();
        engine = new DefaultDecisionEngine(alternativeGenerator, alternativeEvaluator);
    }

    @Test
    @DisplayName("Debe producir una decisión completa para un resultado favorable")
    void shouldProduceCompleteDecisionForFavorableResult() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.FAVORABLE);

        DecisionOutcome outcome = engine.decide(evaluation);

        assertThat(outcome.evaluation()).isEqualTo(evaluation);
        assertThat(outcome.alternatives()).isNotEmpty();
        assertThat(outcome.recommendation()).isNotNull();
        assertThat(outcome.recommendation().action()).isEqualTo(DecisionAction.CLOSE_CYCLE);
        assertThat(outcome.transitionProposal()).isNotNull();
        assertThat(outcome.transitionProposal().proposedState())
                .isEqualTo(CycleState.E10_APRENDIENDO);
    }

    @Test
    @DisplayName("Debe producir una decisión completa para un resultado parcial")
    void shouldProduceCompleteDecisionForPartialResult() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.PARTIAL);

        DecisionOutcome outcome = engine.decide(evaluation);

        assertThat(outcome.recommendation().action()).isEqualTo(DecisionAction.ADJUST_PLAN);
        assertThat(outcome.transitionProposal().proposedState())
                .isEqualTo(CycleState.E04_PLANIFICANDO);
    }

    @Test
    @DisplayName("Debe producir una decisión completa para un resultado inconcluso")
    void shouldProduceCompleteDecisionForInconclusiveResult() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.INCONCLUSIVE);

        DecisionOutcome outcome = engine.decide(evaluation);

        assertThat(outcome.recommendation().action()).isEqualTo(DecisionAction.COLLECT_EVIDENCE);
        assertThat(outcome.transitionProposal().proposedState())
                .isEqualTo(CycleState.E02_OBSERVANDO);
    }

    @Test
    @DisplayName("Debe rechazar un resultado de evaluación nulo")
    void shouldRejectNullEvaluationResult() {
        assertThatThrownBy(() -> engine.decide(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obligatorio");
    }

    @Test
    @DisplayName("Debe rechazar una decisión si no hay alternativas admisibles")
    void shouldRejectDecisionWhenNoAdmissibleAlternatives() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.RISK_INADMISSIBLE);

        assertThatThrownBy(() -> engine.decide(evaluation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alternativa admisible");
    }

    @Test
    @DisplayName("Debe garantizar que la recomendación sea una alternativa admisible")
    void shouldEnsureRecommendationIsAdmissible() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.FAVORABLE);

        DecisionOutcome outcome = engine.decide(evaluation);

        boolean recommendedIsAdmissible = outcome.alternatives().stream()
                .anyMatch(alt -> alt.action() == outcome.recommendation().action()
                        && alt.admissible());

        assertThat(recommendedIsAdmissible).isTrue();
    }

    @Test
    @DisplayName("Debe rechazar un constructor con generador nulo")
    void shouldRejectNullAlternativeGenerator() {
        assertThatThrownBy(() ->
                new DefaultDecisionEngine(null, alternativeEvaluator))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("generador");
    }

    @Test
    @DisplayName("Debe rechazar un constructor con evaluador nulo")
    void shouldRejectNullAlternativeEvaluator() {
        assertThatThrownBy(() ->
                new DefaultDecisionEngine(alternativeGenerator, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("evaluador");
    }

    @Test
    @DisplayName("Debe construir un rationale que justifique la recomendación")
    void shouldBuildRecommendationRationale() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.FAVORABLE);

        DecisionOutcome outcome = engine.decide(evaluation);

        assertThat(outcome.recommendation().rationale())
                .isNotBlank()
                .contains(evaluation.classification().getCode());
    }

    @Test
    @DisplayName("Debe proponer una transición desde el estado DECIDIENDO")
    void shouldProposeTransitionFromDecidingState() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.FAVORABLE);

        DecisionOutcome outcome = engine.decide(evaluation);

        assertThat(outcome.transitionProposal().sourceState())
                .isEqualTo(CycleState.E09_DECIDIENDO);
    }

    private EvaluationResult createEvaluation(ResultClassification classification) {
        EvidenceAssessment evidence = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(),
                "Evidencia suficiente",
                Instant.now()
        );

        return new EvaluationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                classification,
                evidence,
                ConfidenceLevel.HIGH,
                "Evaluación de prueba",
                Instant.now()
        );
    }

    /**
     * Implementación simulada del generador para pruebas.
     */
    private static class StubAlternativeGenerator implements AlternativeGenerator {
        @Override
        public List<AlternativeAssessment> generateAlternatives(EvaluationResult evaluation) {
            ResultClassification classification = evaluation.classification();

            if (classification == ResultClassification.FAVORABLE) {
                return List.of(
                        new AlternativeAssessment(
                                DecisionAction.CLOSE_CYCLE,
                                true,
                                "El resultado es favorable",
                                "Cierre controlado",
                                List.of(),
                                "Bajo",
                                false
                        )
                );
            } else if (classification == ResultClassification.PARTIAL) {
                return List.of(
                        new AlternativeAssessment(
                                DecisionAction.ADJUST_PLAN,
                                true,
                                "Algunos criterios permanecen pendientes",
                                "Ajustar la intervención",
                                List.of(),
                                "Medio",
                                true
                        )
                );
            } else if (classification == ResultClassification.INCONCLUSIVE) {
                return List.of(
                        new AlternativeAssessment(
                                DecisionAction.COLLECT_EVIDENCE,
                                true,
                                "La evidencia es insuficiente",
                                "Recopilar información adicional",
                                List.of(),
                                "Bajo",
                                false
                        )
                );
            }

            return List.of(
                    new AlternativeAssessment(
                            DecisionAction.SUSPEND_CYCLE,
                            false,
                            "No hay alternativas admisibles",
                            null,
                            List.of(),
                            null,
                            false
                    )
            );
        }
    }

    /**
     * Implementación simulada del evaluador para pruebas.
     */
    private static class StubAlternativeEvaluator implements AlternativeEvaluator {
        @Override
        public AlternativeAssessment evaluateAlternatives(List<AlternativeAssessment> alternatives) {
            return alternatives.stream()
                    .filter(AlternativeAssessment::admissible)
                    .findFirst()
                    .orElse(null);
        }
    }
}