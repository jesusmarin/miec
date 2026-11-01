package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;
import com.miec.evidence.EvidenceSufficiency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link AlternativeGenerator}.
 *
 * <p>Estas pruebas verifican que una implementación concreta del generador
 * cumple con los invariantes de F07 y F02. Se utiliza una implementación
 * simulada para validar el comportamiento esperado.
 */
@DisplayName("Pruebas del contrato AlternativeGenerator MIEC")
class AlternativeGeneratorTest {

    /**
     * Implementación simulada del generador para pruebas.
     *
     * <p>Esta implementación devuelve alternativas basadas en la clasificación
     * del resultado.
     */
    private static class StubAlternativeGenerator implements AlternativeGenerator {

        @Override
        public List<AlternativeAssessment> generateAlternatives(EvaluationResult evaluationResult) {
            if (evaluationResult == null) {
                throw new NullPointerException("El resultado de evaluación es obligatorio");
            }

            ResultClassification classification = evaluationResult.classification();

            if (classification == ResultClassification.FAVORABLE) {
                return List.of(
                        new AlternativeAssessment(
                                DecisionAction.CLOSE_CYCLE,
                                true,
                                "El resultado es favorable y cumple los criterios",
                                "Cierre controlado del ciclo",
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
                                "Ajustar la intervención para abordar criterios pendientes",
                                List.of("Riesgo de iteración adicional"),
                                "Medio",
                                true
                        ),
                        new AlternativeAssessment(
                                DecisionAction.CLOSE_CYCLE,
                                false,
                                "No se han cumplido todos los criterios obligatorios",
                                null,
                                List.of(),
                                null,
                                false
                        )
                );
            } else if (classification == ResultClassification.INCONCLUSIVE) {
                return List.of(
                        new AlternativeAssessment(
                                DecisionAction.COLLECT_EVIDENCE,
                                true,
                                "La evidencia es insuficiente para concluir",
                                "Recopilar información adicional",
                                List.of(),
                                "Bajo",
                                false
                        )
                );
            }

            return List.of();
        }
    }

    private final AlternativeGenerator generator = new StubAlternativeGenerator();

    @Test
    @DisplayName("Debe generar alternativas para un resultado favorable")
    void shouldGenerateAlternativesForFavorableResult() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.FAVORABLE);

        List<AlternativeAssessment> alternatives = generator.generateAlternatives(evaluation);

        assertThat(alternatives).isNotEmpty();
        assertThat(alternatives).hasSize(1);
        assertThat(alternatives.get(0).action()).isEqualTo(DecisionAction.CLOSE_CYCLE);
        assertThat(alternatives.get(0).admissible()).isTrue();
    }

    @Test
    @DisplayName("Debe generar alternativas para un resultado parcial")
    void shouldGenerateAlternativesForPartialResult() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.PARTIAL);

        List<AlternativeAssessment> alternatives = generator.generateAlternatives(evaluation);

        assertThat(alternatives).isNotEmpty();
        assertThat(alternatives).hasSize(2);
        assertThat(alternatives.get(0).action()).isEqualTo(DecisionAction.ADJUST_PLAN);
        assertThat(alternatives.get(0).admissible()).isTrue();
        assertThat(alternatives.get(1).action()).isEqualTo(DecisionAction.CLOSE_CYCLE);
        assertThat(alternatives.get(1).admissible()).isFalse();
    }

    @Test
    @DisplayName("Debe generar alternativas para un resultado inconcluso")
    void shouldGenerateAlternativesForInconclusiveResult() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.INCONCLUSIVE);

        List<AlternativeAssessment> alternatives = generator.generateAlternatives(evaluation);

        assertThat(alternatives).isNotEmpty();
        assertThat(alternatives.get(0).action()).isEqualTo(DecisionAction.COLLECT_EVIDENCE);
    }

    @Test
    @DisplayName("Debe rechazar un resultado de evaluación nulo")
    void shouldRejectNullEvaluationResult() {
        assertThatThrownBy(() -> generator.generateAlternatives(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obligatorio");
    }

    @Test
    @DisplayName("Debe devolver una lista vacía si no hay alternativas aplicables")
    void shouldReturnEmptyListWhenNoAlternativesApplicable() {
        EvaluationResult evaluation = createEvaluation(ResultClassification.RISK_INADMISSIBLE);

        List<AlternativeAssessment> alternatives = generator.generateAlternatives(evaluation);

        assertThat(alternatives).isEmpty();
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