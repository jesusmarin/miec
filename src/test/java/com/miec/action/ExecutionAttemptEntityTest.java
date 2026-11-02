
package com.miec.action;

import com.miec.action.ExecutionAttemptEntity.EffectOutcome;
import com.miec.action.ExecutionAttemptEntity.Status;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias del modelo de intentos IR-009.
 *
 * <p>Verifican identidad, estados, transiciones
 * y protección del historial de ejecución.
 */
@DisplayName(
        "MIEC-F03 IR-009 - Entidad de intentos de ejecución"
)
class ExecutionAttemptEntityTest {

    /**
     * Construye un intento de ejecución válido.
     *
     * @return intento en estado pendiente
     */
    private ExecutionAttemptEntity createAttempt() {
        return new ExecutionAttemptEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "miec-actor"
        );
    }

    @Test
    @DisplayName("Debe crear un intento pendiente con identidad planificada")
    void shouldCreatePendingAttempt() {
        var attempt = createAttempt();

        assertAll(
                () -> assertNotNull(attempt.getPlannedActionId()),
                () -> assertNull(attempt.getExecutionId()),
                () -> assertEquals(1, attempt.getAttemptNumber()),
                () -> assertEquals(Status.PENDING, attempt.getStatus()),
                () -> assertEquals(
                        EffectOutcome.EFFECT_UNKNOWN,
                        attempt.getEffectOutcome()
                )
        );
    }

    @Test
    @DisplayName("Debe iniciar una ejecución pendiente")
    void shouldStartPendingAttempt() {
        var attempt = createAttempt();

        attempt.start();

        assertEquals(Status.IN_PROGRESS, attempt.getStatus());
        assertNotNull(attempt.getStartedAt());
    }

    @Test
    @DisplayName("Debe registrar una ejecución completada")
    void shouldCompleteExecution() {
        var attempt = createAttempt();

        attempt.start();
        attempt.complete("result:123");

        assertAll(
                () -> assertEquals(Status.COMPLETED, attempt.getStatus()),
                () -> assertEquals(
                        EffectOutcome.EFFECT_APPLIED,
                        attempt.getEffectOutcome()
                ),
                () -> assertEquals(
                        "result:123", attempt.getResultReference()
                ),
                () -> assertNotNull(attempt.getCompletedAt())
        );
    }

    @Test
    @DisplayName("Debe registrar un fallo sin efectos confirmados")
    void shouldRecordFailureWithoutEffects() {
        var attempt = createAttempt();

        attempt.start();
        attempt.fail(
                EffectOutcome.NO_EFFECT_CONFIRMED,
                "error:456"
        );

        assertEquals(Status.FAILED, attempt.getStatus());
        assertEquals(
                EffectOutcome.NO_EFFECT_CONFIRMED,
                attempt.getEffectOutcome()
        );
        assertEquals("error:456", attempt.getErrorReference());
    }

    @Test
    @DisplayName("Debe registrar como desconocido un resultado incierto")
    void shouldRecordUnknownOutcome() {
        var attempt = createAttempt();

        attempt.start();
        attempt.markUnknown("timeout:789");

        assertEquals(Status.UNKNOWN, attempt.getStatus());
        assertEquals(
                EffectOutcome.EFFECT_UNKNOWN,
                attempt.getEffectOutcome()
        );
    }

    @Test
    @DisplayName("Debe impedir completar un intento no iniciado")
    void shouldRejectCompletionBeforeStart() {
        var attempt = createAttempt();

        assertThrows(
                IllegalStateException.class,
                () -> attempt.complete("result:123")
        );
    }

    @Test
    @DisplayName("Debe impedir reiniciar una ejecución finalizada")
    void shouldRejectRestartOfCompletedAttempt() {
        var attempt = createAttempt();

        attempt.start();
        attempt.complete("result:123");

        assertThrows(
                IllegalStateException.class,
                attempt::start
        );
    }

    @Test
    @DisplayName("Debe declarar identidad JPA y control de versión")
    void shouldHavePersistenceMappings()
            throws NoSuchFieldException {

        assertTrue(
                ExecutionAttemptEntity.class.isAnnotationPresent(
                        Entity.class
                )
        );

        Table table =
                ExecutionAttemptEntity.class.getAnnotation(
                        Table.class
                );

        assertNotNull(table);
        assertEquals("execution_attempt", table.name());

        Field idField =
                ExecutionAttemptEntity.class.getDeclaredField(
                        "executionId"
                );

        Field versionField =
                ExecutionAttemptEntity.class.getDeclaredField(
                        "version"
                );

        assertTrue(idField.isAnnotationPresent(Id.class));
        assertTrue(
                versionField.isAnnotationPresent(Version.class)
        );
    }
}
