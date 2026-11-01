package com.miec.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link TransitionValidator}.
 *
 * <p>Estas pruebas verifican que una implementación concreta del validador
 * cumple con los invariantes de F07 y F01. Se utiliza una implementación
 * simulada para validar el comportamiento esperado.
 */
@DisplayName("Pruebas del contrato TransitionValidator MIEC")
class TransitionValidatorTest {

    /**
     * Implementación simulada del validador para pruebas.
     *
     * <p>Esta implementación valida transiciones básicas según F01.
     */
    private static class StubTransitionValidator implements TransitionValidator {

        @Override
        public TransitionValidationResult validate(TransitionValidationRequest request) {
            if (request == null) {
                throw new NullPointerException("El request es obligatorio");
            }

            CycleState current = request.currentState();
            CycleState proposed = request.proposedState();

            boolean allowed = isValidTransition(current, proposed);
            String reason;
            List<String> violatedInvariants;

            if (allowed) {
                reason = "Transición permitida conforme a F01";
                violatedInvariants = List.of();
            } else {
                reason = "Transición no permitida desde " + current + " hacia " + proposed;
                violatedInvariants = List.of("INV-02");
            }

            return new TransitionValidationResult(
                    allowed,
                    current,
                    proposed,
                    reason,
                    violatedInvariants,
                    Instant.now()
            );
        }

        private boolean isValidTransition(CycleState current, CycleState next) {
            return switch (current) {
                case E00_INACTIVO -> next == CycleState.E01_INICIALIZANDO;
                case E01_INICIALIZANDO -> next == CycleState.E02_OBSERVANDO
                        || next == CycleState.E12_SUSPENDIDO;
                case E02_OBSERVANDO -> next == CycleState.E03_DIAGNOSTICANDO
                        || next == CycleState.E12_SUSPENDIDO;
                case E14_FINALIZADO, E15_ABORTADO -> false;
                default -> false;
            };
        }
    }

    private final TransitionValidator validator = new StubTransitionValidator();

    @Test
    @DisplayName("Debe permitir la transición de E00_INACTIVO a E01_INICIALIZANDO")
    void shouldAllowTransitionFromInactiveToInitializing() {
        TransitionValidationRequest request = new TransitionValidationRequest(
                UUID.randomUUID(),
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                null,
                Instant.now()
        );

        TransitionValidationResult result = validator.validate(request);

        assertThat(result.allowed()).isTrue();
        assertThat(result.isRejected()).isFalse();
        assertThat(result.violatedInvariants()).isEmpty();
    }

    @Test
    @DisplayName("Debe permitir la transición de E01_INICIALIZANDO a E02_OBSERVANDO")
    void shouldAllowTransitionFromInitializingToObserving() {
        TransitionValidationRequest request = new TransitionValidationRequest(
                UUID.randomUUID(),
                CycleState.E01_INICIALIZANDO,
                CycleState.E02_OBSERVANDO,
                null,
                Instant.now()
        );

        TransitionValidationResult result = validator.validate(request);

        assertThat(result.allowed()).isTrue();
        assertThat(result.isRejected()).isFalse();
    }

    @Test
    @DisplayName("Debe rechazar la transición de E14_FINALIZADO a cualquier estado")
    void shouldRejectTransitionFromFinalizedState() {
        TransitionValidationRequest request = new TransitionValidationRequest(
                UUID.randomUUID(),
                CycleState.E14_FINALIZADO,
                CycleState.E01_INICIALIZANDO,
                null,
                Instant.now()
        );

        TransitionValidationResult result = validator.validate(request);

        assertThat(result.allowed()).isFalse();
        assertThat(result.isRejected()).isTrue();
        assertThat(result.violatedInvariants()).contains("INV-02");
    }

    @Test
    @DisplayName("Debe rechazar una transición no definida en F01")
    void shouldRejectUndefinedTransition() {
        TransitionValidationRequest request = new TransitionValidationRequest(
                UUID.randomUUID(),
                CycleState.E00_INACTIVO,
                CycleState.E07_EJECUTANDO,
                null,
                Instant.now()
        );

        TransitionValidationResult result = validator.validate(request);

        assertThat(result.allowed()).isFalse();
        assertThat(result.isRejected()).isTrue();
        assertThat(result.reason()).contains("no permitida");
    }

    @Test
    @DisplayName("Debe rechazar un request nulo")
    void shouldRejectNullRequest() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obligatorio");
    }

    @Test
    @DisplayName("Debe proporcionar un motivo cuando la transición es rechazada")
    void shouldProvideReasonWhenRejected() {
        TransitionValidationRequest request = new TransitionValidationRequest(
                UUID.randomUUID(),
                CycleState.E14_FINALIZADO,
                CycleState.E01_INICIALIZANDO,
                null,
                Instant.now()
        );

        TransitionValidationResult result = validator.validate(request);

        assertThat(result.reason()).isNotBlank();
        assertThat(result.validatedAt()).isNotNull();
    }
}