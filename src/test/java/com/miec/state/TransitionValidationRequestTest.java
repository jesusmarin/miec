package com.miec.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link TransitionValidationRequest}.
 *
 * <p>Verifican las invariantes de construcción, la normalización de datos
 * y el tratamiento del contexto de transición como opcional.
 */
@DisplayName("Pruebas de la solicitud de validación de transición MIEC")
class TransitionValidationRequestTest {

    private final UUID cycleId = UUID.randomUUID();
    private final Instant validatedAt = Instant.now();

    @Test
    @DisplayName("Debe crear una solicitud válida sin contexto de transición")
    void shouldCreateValidRequestWithoutContext() {
        TransitionValidationRequest request = new TransitionValidationRequest(
                cycleId,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                null,
                validatedAt
        );

        assertThat(request.cycleId()).isEqualTo(cycleId);
        assertThat(request.currentState()).isEqualTo(CycleState.E00_INACTIVO);
        assertThat(request.proposedState()).isEqualTo(CycleState.E01_INICIALIZANDO);
        assertThat(request.transitionContext()).isNull();
        assertThat(request.validatedAt()).isEqualTo(validatedAt);
        assertThat(request.hasTransitionContext()).isFalse();
    }

    @Test
    @DisplayName("Debe crear una solicitud válida con contexto de transición completo")
    void shouldCreateValidRequestWithContext() {
        TransitionValidationRequest.TransitionContext context =
                new TransitionValidationRequest.TransitionContext(
                        "auth-001",
                        "evidence-001",
                        "EVALUATION_COMPLETED",
                        "actor-001"
                );

        TransitionValidationRequest request = new TransitionValidationRequest(
                cycleId,
                CycleState.E08_VERIFICANDO,
                CycleState.E09_DECIDIENDO,
                context,
                validatedAt
        );

        assertThat(request.transitionContext()).isNotNull();
        assertThat(request.hasTransitionContext()).isTrue();
        assertThat(request.transitionContext().authorizationReference()).isEqualTo("auth-001");
        assertThat(request.transitionContext().evidenceReference()).isEqualTo("evidence-001");
        assertThat(request.transitionContext().triggerEvent()).isEqualTo("EVALUATION_COMPLETED");
        assertThat(request.transitionContext().actorId()).isEqualTo("actor-001");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin identificador de ciclo")
    void shouldRejectNullCycleId() {
        assertThatThrownBy(() -> new TransitionValidationRequest(
                null,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                null,
                validatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ciclo");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin estado actual")
    void shouldRejectNullCurrentState() {
        assertThatThrownBy(() -> new TransitionValidationRequest(
                cycleId,
                null,
                CycleState.E01_INICIALIZANDO,
                null,
                validatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("estado actual");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin estado propuesto")
    void shouldRejectNullProposedState() {
        assertThatThrownBy(() -> new TransitionValidationRequest(
                cycleId,
                CycleState.E00_INACTIVO,
                null,
                null,
                validatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("estado propuesto");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud donde el estado actual y propuesto son iguales")
    void shouldRejectSameCurrentAndProposedState() {
        assertThatThrownBy(() -> new TransitionValidationRequest(
                cycleId,
                CycleState.E00_INACTIVO,
                CycleState.E00_INACTIVO,
                null,
                validatedAt
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diferentes");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin instante de validación")
    void shouldRejectNullValidatedAt() {
        assertThatThrownBy(() -> new TransitionValidationRequest(
                cycleId,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                null,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("instante de validación");
    }

    @Test
    @DisplayName("Debe crear un contexto de transición válido con todos los campos")
    void shouldCreateTransitionContextWithAllFields() {
        TransitionValidationRequest.TransitionContext context =
                new TransitionValidationRequest.TransitionContext(
                        "auth-001",
                        "evidence-001",
                        "EVALUATION_COMPLETED",
                        "actor-001"
                );

        assertThat(context.authorizationReference()).isEqualTo("auth-001");
        assertThat(context.evidenceReference()).isEqualTo("evidence-001");
        assertThat(context.triggerEvent()).isEqualTo("EVALUATION_COMPLETED");
        assertThat(context.actorId()).isEqualTo("actor-001");
        assertThat(context.hasAuthorization()).isTrue();
        assertThat(context.hasEvidence()).isTrue();
        assertThat(context.hasTriggerEvent()).isTrue();
        assertThat(context.hasActor()).isTrue();
    }

    @Test
    @DisplayName("Debe crear un contexto de transición válido sin campos opcionales")
    void shouldCreateTransitionContextWithoutOptionalFields() {
        TransitionValidationRequest.TransitionContext context =
                new TransitionValidationRequest.TransitionContext(null, null, null, null);

        assertThat(context.authorizationReference()).isNull();
        assertThat(context.evidenceReference()).isNull();
        assertThat(context.triggerEvent()).isNull();
        assertThat(context.actorId()).isNull();
        assertThat(context.hasAuthorization()).isFalse();
        assertThat(context.hasEvidence()).isFalse();
        assertThat(context.hasTriggerEvent()).isFalse();
        assertThat(context.hasActor()).isFalse();
    }

    @Test
    @DisplayName("Debe normalizar los campos del contexto eliminando espacios sobrantes")
    void shouldTrimTransitionContextFields() {
        TransitionValidationRequest.TransitionContext context =
                new TransitionValidationRequest.TransitionContext(
                        "  auth-001  ",
                        "  evidence-001  ",
                        "  EVALUATION_COMPLETED  ",
                        "  actor-001  "
                );

        assertThat(context.authorizationReference()).isEqualTo("auth-001");
        assertThat(context.evidenceReference()).isEqualTo("evidence-001");
        assertThat(context.triggerEvent()).isEqualTo("EVALUATION_COMPLETED");
        assertThat(context.actorId()).isEqualTo("actor-001");
    }

    @Test
    @DisplayName("Debe convertir campos en blanco del contexto a nulo")
    void shouldConvertBlankContextFieldsToNull() {
        TransitionValidationRequest.TransitionContext context =
                new TransitionValidationRequest.TransitionContext(
                        "   ",
                        "   ",
                        "   ",
                        "   "
                );

        assertThat(context.authorizationReference()).isNull();
        assertThat(context.evidenceReference()).isNull();
        assertThat(context.triggerEvent()).isNull();
        assertThat(context.actorId()).isNull();
    }
}