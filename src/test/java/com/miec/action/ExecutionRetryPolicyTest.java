
package com.miec.action;

import com.miec.action.ExecutionRetryPolicy.EffectOutcome;
import com.miec.action.ExecutionRetryPolicy.ExecutionStatus;
import com.miec.action.ExecutionRetryPolicy.RetryRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de reintentos controlados IR-009.
 *
 * <p>Comprueban que solo un intento fallido,
 * sin efectos y con autorización válida,
 * pueda ejecutarse nuevamente.
 */
@DisplayName("MIEC-F03 IR-009 - Política de reintentos")
class ExecutionRetryPolicyTest {

    private final ExecutionRetryPolicy policy =
            new ExecutionRetryPolicy();

    /**
     * Construye un contexto con dos intentos disponibles.
     */
    private RetryRequest request(
            ExecutionStatus status,
            EffectOutcome outcome,
            boolean authorized
    ) {
        return new RetryRequest(
                status, outcome, 1, 3, authorized
        );
    }

    @Test
    @DisplayName(
            "Debe permitir reintentar un fallo sin efectos confirmados"
    )
    void shouldAllowSafeRetry() {
        var decision = policy.evaluate(request(
                ExecutionStatus.FAILED,
                EffectOutcome.NO_EFFECT_CONFIRMED,
                true
        ));

        assertTrue(decision.allowed());
        assertEquals(2, decision.nextAttemptNumber());
    }

    @Test
    @DisplayName("Debe impedir repetir una ejecución completada")
    void shouldRejectCompleted() {
        assertFalse(policy.evaluate(request(
                ExecutionStatus.COMPLETED,
                EffectOutcome.EFFECT_APPLIED,
                true
        )).allowed());
    }

    @Test
    @DisplayName("Debe impedir reintentar una ejecución en curso")
    void shouldRejectInProgress() {
        assertFalse(policy.evaluate(request(
                ExecutionStatus.IN_PROGRESS,
                EffectOutcome.EFFECT_UNKNOWN,
                true
        )).allowed());
    }

    @Test
    @DisplayName("Debe impedir reintentos con resultado desconocido")
    void shouldRejectUnknown() {
        assertFalse(policy.evaluate(request(
                ExecutionStatus.UNKNOWN,
                EffectOutcome.EFFECT_UNKNOWN,
                true
        )).allowed());
    }

    @Test
    @DisplayName("Debe impedir reintentar cuando existen efectos aplicados")
    void shouldRejectAppliedEffects() {
        assertFalse(policy.evaluate(request(
                ExecutionStatus.FAILED,
                EffectOutcome.EFFECT_APPLIED,
                true
        )).allowed());
    }

    @Test
    @DisplayName("Debe impedir reintentos sin autorización válida")
    void shouldRejectInvalidAuthorization() {
        assertFalse(policy.evaluate(request(
                ExecutionStatus.FAILED,
                EffectOutcome.NO_EFFECT_CONFIRMED,
                false
        )).allowed());
    }

    @Test
    @DisplayName("Debe impedir superar el máximo de intentos")
    void shouldRejectAttemptLimit() {
        var request = new RetryRequest(
                ExecutionStatus.FAILED,
                EffectOutcome.NO_EFFECT_CONFIRMED,
                3,
                3,
                true
        );

        assertFalse(policy.evaluate(request).allowed());
    }

    @Test
    @DisplayName("Debe impedir reintentar una acción pendiente")
    void shouldRejectPending() {
        assertFalse(policy.evaluate(request(
                ExecutionStatus.PENDING,
                EffectOutcome.NO_EFFECT_CONFIRMED,
                true
        )).allowed());
    }
}
