package com.miec.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link AlternativeEvaluator}.
 *
 * <p>Estas pruebas verifican que una implementación concreta del evaluador
 * cumple con los invariantes de F07 y F02.
 */
@DisplayName("Pruebas del contrato AlternativeEvaluator MIEC")
class AlternativeEvaluatorTest {

    /**
     * Implementación simulada del evaluador para pruebas.
     *
     * <p>Esta implementación selecciona la primera alternativa admisible.
     */
    private static class StubAlternativeEvaluator implements AlternativeEvaluator {

        @Override
        public AlternativeAssessment evaluateAlternatives(List<AlternativeAssessment> alternatives) {
            if (alternatives == null) {
                throw new NullPointerException("La lista de alternativas es obligatoria");
            }

            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("La lista de alternativas no puede estar vacía");
            }

            return alternatives.stream()
                    .filter(AlternativeAssessment::admissible)
                    .findFirst()
                    .orElse(null);
        }
    }

    private final AlternativeEvaluator evaluator = new StubAlternativeEvaluator();

    @Test
    @DisplayName("Debe seleccionar la primera alternativa admisible")
    void shouldSelectFirstAdmissibleAlternative() {
        AlternativeAssessment admissible = new AlternativeAssessment(
                DecisionAction.ADJUST_PLAN,
                true,
                "Alternativa admisible",
                "Beneficio esperado",
                List.of(),
                "Bajo",
                true
        );

        AlternativeAssessment inadmissible = new AlternativeAssessment(
                DecisionAction.CLOSE_CYCLE,
                false,
                "Alternativa inadmisible",
                null,
                List.of(),
                null,
                false
        );

        AlternativeAssessment recommended = evaluator.evaluateAlternatives(
                List.of(inadmissible, admissible)
        );

        assertThat(recommended).isNotNull();
        assertThat(recommended.action()).isEqualTo(DecisionAction.ADJUST_PLAN);
        assertThat(recommended.admissible()).isTrue();
    }

    @Test
    @DisplayName("Debe devolver nulo si no hay alternativas admisibles")
    void shouldReturnNullWhenNoAdmissibleAlternatives() {
        AlternativeAssessment inadmissible = new AlternativeAssessment(
                DecisionAction.CLOSE_CYCLE,
                false,
                "Alternativa inadmisible",
                null,
                List.of(),
                null,
                false
        );

        AlternativeAssessment recommended = evaluator.evaluateAlternatives(
                List.of(inadmissible)
        );

        assertThat(recommended).isNull();
    }

    @Test
    @DisplayName("Debe rechazar una lista de alternativas nula")
    void shouldRejectNullAlternativesList() {
        assertThatThrownBy(() -> evaluator.evaluateAlternatives(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obligatoria");
    }

    @Test
    @DisplayName("Debe rechazar una lista de alternativas vacía")
    void shouldRejectEmptyAlternativesList() {
        assertThatThrownBy(() -> evaluator.evaluateAlternatives(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vacía");
    }
}