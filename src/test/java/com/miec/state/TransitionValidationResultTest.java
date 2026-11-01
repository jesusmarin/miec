package com.miec.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link TransitionValidationResult}.
 *
 * <p>Verifican las invariantes de construcción, la normalización de datos
 * y el tratamiento de las invariantes violadas.
 */
@DisplayName("Pruebas del resultado de validación de transición MIEC")
class TransitionValidationResultTest {

    private final Instant validatedAt = Instant.now();

    @Test
    @DisplayName("Debe crear un resultado válido indicando que la transición es permitida")
    void shouldCreateValidAllowedResult() {
        TransitionValidationResult result = new TransitionValidationResult(
                true,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                "Transición permitida conforme a F01",
                List.of(),
                validatedAt
        );

        assertThat(result.allowed()).isTrue();
        assertThat(result.currentState()).isEqualTo(CycleState.E00_INACTIVO);
        assertThat(result.proposedState()).isEqualTo(CycleState.E01_INICIALIZANDO);
        assertThat(result.reason()).isEqualTo("Transición permitida conforme a F01");
        assertThat(result.violatedInvariants()).isEmpty();
        assertThat(result.validatedAt()).isEqualTo(validatedAt);
        assertThat(result.isRejected()).isFalse();
        assertThat(result.hasViolatedInvariants()).isFalse();
        assertThat(result.violatedInvariantsCount()).isZero();
    }

    @Test
    @DisplayName("Debe crear un resultado válido indicando que la transición es rechazada")
    void shouldCreateValidRejectedResult() {
        TransitionValidationResult result = new TransitionValidationResult(
                false,
                CycleState.E14_FINALIZADO,
                CycleState.E01_INICIALIZANDO,
                "No se puede modificar un ciclo terminado",
                List.of("INV-08"),
                validatedAt
        );

        assertThat(result.allowed()).isFalse();
        assertThat(result.currentState()).isEqualTo(CycleState.E14_FINALIZADO);
        assertThat(result.proposedState()).isEqualTo(CycleState.E01_INICIALIZANDO);
        assertThat(result.reason()).isEqualTo("No se puede modificar un ciclo terminado");
        assertThat(result.violatedInvariants()).containsExactly("INV-08");
        assertThat(result.validatedAt()).isEqualTo(validatedAt);
        assertThat(result.isRejected()).isTrue();
        assertThat(result.hasViolatedInvariants()).isTrue();
        assertThat(result.violatedInvariantsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Debe rechazar un resultado sin estado actual")
    void shouldRejectNullCurrentState() {
        assertThatThrownBy(() -> new TransitionValidationResult(
                true,
                null,
                CycleState.E01_INICIALIZANDO,
                "Transición permitida",
                List.of(),
                validatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("estado actual");
    }

    @Test
    @DisplayName("Debe rechazar un resultado sin estado propuesto")
    void shouldRejectNullProposedState() {
        assertThatThrownBy(() -> new TransitionValidationResult(
                true,
                CycleState.E00_INACTIVO,
                null,
                "Transición permitida",
                List.of(),
                validatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("estado propuesto");
    }

    @Test
    @DisplayName("Debe rechazar un resultado sin motivo")
    void shouldRejectNullReason() {
        assertThatThrownBy(() -> new TransitionValidationResult(
                true,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                null,
                List.of(),
                validatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("motivo");
    }

    @Test
    @DisplayName("Debe rechazar un resultado con motivo en blanco")
    void shouldRejectBlankReason() {
        assertThatThrownBy(() -> new TransitionValidationResult(
                true,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                "   ",
                List.of(),
                validatedAt
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("motivo");
    }

    @Test
    @DisplayName("Debe rechazar un resultado sin lista de invariantes violadas")
    void shouldRejectNullViolatedInvariants() {
        assertThatThrownBy(() -> new TransitionValidationResult(
                false,
                CycleState.E14_FINALIZADO,
                CycleState.E01_INICIALIZANDO,
                "Transición no permitida",
                null,
                validatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("invariantes violadas");
    }

    @Test
    @DisplayName("Debe rechazar un resultado sin instante de validación")
    void shouldRejectNullValidatedAt() {
        assertThatThrownBy(() -> new TransitionValidationResult(
                true,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                "Transición permitida",
                List.of(),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("instante de validación");
    }

    @Test
    @DisplayName("Debe normalizar el motivo eliminando espacios sobrantes")
    void shouldTrimReason() {
        TransitionValidationResult result = new TransitionValidationResult(
                true,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                "  Transición permitida  ",
                List.of(),
                validatedAt
        );

        assertThat(result.reason()).isEqualTo("Transición permitida");
    }

    @Test
    @DisplayName("Debe filtrar elementos nulos y en blanco de la lista de invariantes violadas")
    void shouldFilterNullAndBlankViolatedInvariants() {
        List<String> withNullsAndBlanks = Arrays.asList(
                "INV-01",
                null,
                "   ",
                "INV-02",
                ""
        );

        TransitionValidationResult result = new TransitionValidationResult(
                false,
                CycleState.E14_FINALIZADO,
                CycleState.E01_INICIALIZANDO,
                "Transición no permitida",
                withNullsAndBlanks,
                validatedAt
        );

        assertThat(result.violatedInvariants()).containsExactly("INV-01", "INV-02");
        assertThat(result.violatedInvariantsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Debe exponer una lista de invariantes violadas inmutable")
    void shouldExposeImmutableViolatedInvariantsList() {
        TransitionValidationResult result = new TransitionValidationResult(
                false,
                CycleState.E14_FINALIZADO,
                CycleState.E01_INICIALIZANDO,
                "Transición no permitida",
                List.of("INV-08"),
                validatedAt
        );

        assertThatThrownBy(() -> result.violatedInvariants().add("INV-09"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}