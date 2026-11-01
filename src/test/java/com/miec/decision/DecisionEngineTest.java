package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;
import com.miec.evidence.EvidenceSufficiency;
import com.miec.state.CycleState;
import com.miec.state.TransitionProposal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link DecisionEngine}.
 *
 * <p>Estas pruebas verifican que una implementación concreta del motor
 * cumple con los invariantes de F07 y F02. Se utiliza una implementación
 * simulada para validar el comportamiento esperado.
 */
@DisplayName("Pruebas del contrato DecisionEngine MIEC")
class DecisionEngineTest {

    /**
     * Implementación simulada del motor de decisión para pruebas.
     *
     * <p>Esta implementación orquesta los componentes de forma simplificada.
     */
    private static class StubDecisionEngine implements DecisionEngine {

        @Override
        public DecisionOutcome decide(EvaluationResult evaluationResult) {
            if (evaluationResult == null) {
                throw new NullPointerException("El resultado de evaluación es obligatorio");
            }

            // Generar alternativas según la clasificación
            List<AlternativeAssessment> alternatives = generateAlternatives(evaluationResult);

            // Evaluar alternativas y seleccionar la recomendación
            AlternativeAssessment recommended = alternatives.stream()
                    .filter(AlternativeAssessment::admissible)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No existe ninguna alternativa admisible"));

            DecisionRecommendation recommendation = new DecisionRecommendation(
                    recommended.action(),
                    "Recomendación basada en la evaluación",
                    evaluationResult.evidenceAssessment().evidenceIds(),
                    Instant.now()
            );

            // Proponer transición según la acción
            TransitionProposal transition = proposeTransition(recommended.action());

            return new DecisionOutcome(
                    evaluationResult,
                    alternatives,
                    recommendation,
                    transition
            );
        }

        private List<AlternativeAssessment> generateAlternatives(EvaluationResult evaluation) {
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

        private TransitionProposal proposeTransition(DecisionAction action) {
            CycleState targetState = switch (action) {
                case CLOSE_CYCLE -> CycleState.E14_FINALIZADO;
                case ADJUST_PLAN -> CycleState.E04_PLANIFICANDO;
                case COLLECT_EVIDENCE -> CycleState.E02_OBSERVANDO;
                default -> CycleState.E12_SUSPENDIDO;
            };

            return new TransitionProposal(
                    CycleState.E09_DECIDIENDO,
                    targetState,
                    "Transición propuesta según la recomendación",
                    Instant.now()
            );
        }
    }

    private final DecisionEngine engine = new StubDecisionEngine();

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
        assertThat(outcome.transitionProposal().proposedState()).isEqualTo(CycleState.E14_FINALIZADO);
    }

    @Test
    @DisplayName("Debe producir una decisión completa para un resultado parcial")
    void shouldProduceCompleteDecisionForPartialResult() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.PARTIAL);

        DecisionOutcome outcome = engine.decide(evaluation);

        assertThat(outcome.recommendation().action()).isEqualTo(DecisionAction.ADJUST_PLAN);
        assertThat(outcome.transitionProposal().proposedState()).isEqualTo(CycleState.E04_PLANIFICANDO);
    }

    @Test
    @DisplayName("Debe producir una decisión completa para un resultado inconcluso")
    void shouldProduceCompleteDecisionForInconclusiveResult() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.INCONCLUSIVE);

        DecisionOutcome outcome = engine.decide(evaluation);

        assertThat(outcome.recommendation().action()).isEqualTo(DecisionAction.COLLECT_EVIDENCE);
        assertThat(outcome.transitionProposal().proposedState()).isEqualTo(CycleState.E02_OBSERVANDO);
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
}