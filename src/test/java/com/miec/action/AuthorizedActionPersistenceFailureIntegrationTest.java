
package com.miec.action;

import com.miec.action.AuthorizedActionExecutionService.ExecutionCommand;
import com.miec.action.AuthorizedActionExecutionService.ExecutionDeniedException;
import com.miec.action.ExecutionAttemptService.AttemptSnapshot;
import com.miec.action.ExecutionRetryPolicy.EffectOutcome;
import com.miec.action.ExecutionRetryPolicy.ExecutionStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * MIEC-F03 IR-009-C07:
 * Fallo de persistencia después de ejecutar el efecto.
 *
 * <p>Verifica que una ejecución cuyo efecto ya ocurrió,
 * pero cuya confirmación no pudo persistirse, no
 * pueda ejecutarse nuevamente.
 *
 * <p>La prueba utiliza un spy de ExecutionAttemptService
 * para simular el fallo de completeAttempt().
 *
 * <p>Se conserva el funcionamiento real de:
 * <ul>
 *   <li>Validación del plan y autorización.</li>
 *   <li>Creación del intento.</li>
 *   <li>Inicio de ejecución.</li>
 *   <li>Persistencia en execution_attempt.</li>
 *   <li>Política de prevención de reintentos.</li>
 * </ul>
 *
 * <p>No utiliza @Transactional porque el servicio
 * confirma sus transiciones mediante REQUIRES_NEW.
 *
 * <p>La prueba simula un fallo antes de aplicar
 * la actualización COMPLETED. No representa una
 * desconexión real de la base de datos.
 */
@SpringBootTest(properties = {
        "spring.datasource.url="
                + "jdbc:h2:mem:miec_ir009_persistence_failure;"
                + "DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DisplayName(
        "MIEC-F03 IR-009-C07 - Fallo de persistencia posterior al efecto"
)
class AuthorizedActionPersistenceFailureIntegrationTest {

    @Autowired
    private AuthorizedActionExecutionService coordinator;

    /**
     * Spy del servicio real.
     *
     * <p>Permite simular una excepción durante
     * la confirmación del resultado, sin reemplazar
     * la implementación completa.
     */
    @MockitoSpyBean
    private ExecutionAttemptService attempts;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Identificadores del escenario de ejecución.
     */
    private record Fixture(
            UUID planId,
            UUID cycleId,
            UUID actionId,
            UUID authorizationId
    ) {

        /**
         * Construye el comando del coordinador.
         */
        ExecutionCommand command() {
            return new ExecutionCommand(
                    actionId,
                    planId,
                    cycleId,
                    authorizationId,
                    "PROCESS_EXECUTION",
                    "miec-persistence-test"
            );
        }
    }

    /**
     * Crea y confirma un escenario autorizado.
     *
     * @return referencias para ejecutar la acción
     */
    private Fixture fixture() {
        UUID systemId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();

        assertEquals(1, jdbc.update(
                """
                INSERT INTO system_objective (
                    system_id, name
                ) VALUES (?, ?)
                """,
                systemId,
                "Sistema de prueba IR-009-C07"
        ));

        assertEquals(1, jdbc.update(
                """
                INSERT INTO cycle (
                    cycle_id, system_id
                ) VALUES (?, ?)
                """,
                cycleId,
                systemId
        ));

        String actionsJson = """
                {
                  "schemaVersion": "01",
                  "actions": [
                    {
                      "actionId": "%s",
                      "actionType": "PROCESS_EXECUTION"
                    }
                  ]
                }
                """.formatted(actionId);

        assertEquals(1, jdbc.update(
                """
                INSERT INTO intervention_plan (
                    plan_id,
                    cycle_id,
                    status,
                    actions
                )
                VALUES (?, ?, ?, ? FORMAT JSON)
                """,
                planId,
                cycleId,
                "PROPOSED",
                actionsJson
        ));

        assertEquals(1, jdbc.update(
                """
                INSERT INTO authorizations (
                    authorization_id,
                    plan_id,
                    action_id,
                    requested_by,
                    approved_by,
                    scope,
                    status,
                    issued_at,
                    expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                authorizationId,
                planId,
                actionId,
                "requester",
                "approver",
                "EXECUTE_ACTION",
                "APPROVED",
                Timestamp.from(
                        Instant.now().minusSeconds(60)
                ),
                Timestamp.from(
                        Instant.now().plusSeconds(3600)
                )
        ));

        return new Fixture(
                planId,
                cycleId,
                actionId,
                authorizationId
        );
    }

    /**
     * Recupera los intentos persistidos para la acción.
     *
     * @param fixture escenario
     * @return historial ordenado
     */
    private List<AttemptSnapshot> history(Fixture fixture) {
        return attempts.findHistory(
                fixture.planId(),
                fixture.actionId()
        );
    }

    /**
     * Verifica que un error al persistir COMPLETED
     * no permita repetir una operación ya ejecutada.
     *
     * <p>Secuencia:
     * <ol>
     *   <li>Crear y autorizar la acción.</li>
     *   <li>Iniciar el intento.</li>
     *   <li>Ejecutar el efecto exitosamente.</li>
     *   <li>Simular fallo en completeAttempt().</li>
     *  <li> El intento debe quedar UNKNOWN porque markUnknown() consiguió registrar la incertidumbre.</li>
     *   <li>Intentar ejecutar nuevamente por ambos métodos.</li>
     *   <li>Verificar que no se repite el efecto.</li>
     * </ol>
     */
    /**
     * Verifica que el intento permanezca IN_PROGRESS cuando fallan
     * tanto la confirmación COMPLETED como el registro UNKNOWN.
     *
     * <p>Conserva la excepción original y adjunta el fallo de auditoría
     * como excepción suprimida. Comprueba que completedAt permanece
     * nulo y que ambos métodos rechazan repetir el efecto.
     */
    @Test
    @DisplayName(
            "Debe impedir repetir un efecto ejecutado "
                    + "cuando falla su confirmación COMPLETED"
    )
    void shouldPreventDuplicateEffectAfterCompletionPersistenceFailure() {

        // 1. Crear escenario y contador de efectos.
        Fixture fixture = fixture();

        AtomicInteger externalEffects =
                new AtomicInteger();

        // 2. Simular un fallo exclusivo en el registro
        // de COMPLETED. Las demás operaciones del
        // servicio continúan funcionando normalmente.
        doThrow(
                new IllegalStateException(
                        "IR-009-C07: fallo simulado "
                                + "al persistir COMPLETED"
                )
        ).when(attempts).completeAttempt(
                any(UUID.class),
                isNull()
        );

        // 3. El efecto se ejecuta correctamente,
        // pero su confirmación persistente falla.
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.executeAuthorizedTracked(
                        fixture.command(),
                        externalEffects::incrementAndGet
                )
        );

        assertTrue(
                failure.getMessage().contains("IR-009-C07")
        );

        assertEquals(
                1,
                externalEffects.get(),
                "El efecto debe haberse ejecutado una vez"
        );

        // 4. Comprobar que la confirmación fue invocada.
        verify(attempts).completeAttempt(
                any(UUID.class),
                isNull()
        );

        // 5. El intento queda UNKNOWN porque se registró la incertidumbre.
        // No se debe declarar ausencia de efectos.
        List<AttemptSnapshot> recorded = history(fixture);

        assertAll(
                () -> assertEquals(1, recorded.size()),
                () -> assertEquals(
                        1,
                        recorded.getFirst().attemptNumber()
                ),
                () -> assertNotNull(
                        recorded.getFirst().executionId()
                ),
                () -> assertEquals(
                        ExecutionStatus.UNKNOWN,
                        recorded.getFirst().status()
                ),
                () -> assertEquals(
                        EffectOutcome.EFFECT_UNKNOWN,
                        recorded.getFirst().effectOutcome()
                ),
                () -> assertNotNull(
                        recorded.getFirst().startedAt()
                ),
                () -> assertNotNull(
                        recorded.getFirst().completedAt()
                )
        );

        UUID originalExecutionId =
                recorded.getFirst().executionId();

        // 6. Intentar nuevamente por el método trazable.
        assertThrows(
                ExecutionDeniedException.class,
                () -> coordinator.executeAuthorizedTracked(
                        fixture.command(),
                        externalEffects::incrementAndGet
                )
        );

        // 7. Intentar nuevamente por el método heredado.
        assertThrows(
                ExecutionDeniedException.class,
                () -> coordinator.executeAuthorized(
                        fixture.command(),
                        externalEffects::incrementAndGet
                )
        );

        // 8. Confirmar integridad después de los rechazos.
        List<AttemptSnapshot> finalHistory = history(fixture);

        assertAll(
                () -> assertEquals(
                        1,
                        externalEffects.get(),
                        "No se puede volver a producir el efecto"
                ),
                () -> assertEquals(
                        1,
                        finalHistory.size(),
                        "No se deben crear intentos adicionales"
                ),
                () -> assertEquals(
                        originalExecutionId,
                        finalHistory.getFirst().executionId()
                ),
                () -> assertEquals(
                        1,
                        finalHistory.getFirst().attemptNumber()
                ),
                () -> assertEquals(
                        ExecutionStatus.UNKNOWN,
                        finalHistory.getFirst().status(), " El intento debe permanecer en estado incierto"
                ),
                () -> assertEquals(
                        EffectOutcome.EFFECT_UNKNOWN,
                        finalHistory.getFirst().effectOutcome()
                )
        );
    }

    @Test
    @DisplayName(
            "Debe conservar IN_PROGRESS y adjuntar el fallo de auditoría "
                    + "cuando fallan COMPLETED y UNKNOWN"
    )
    void shouldRemainInProgressWhenCompletionAndUnknownPersistenceFail() {
        Fixture fixture = fixture();
        AtomicInteger externalEffects = new AtomicInteger();

        IllegalStateException completionFailure = new IllegalStateException(
                "Fallo al persistir COMPLETED"
        );
        IllegalStateException auditFailure = new IllegalStateException(
                "Fallo al persistir UNKNOWN"
        );

        doThrow(completionFailure)
                .when(attempts)
                .completeAttempt(any(UUID.class), isNull());

        doThrow(auditFailure)
                .when(attempts)
                .markUnknown(
                        any(UUID.class),
                        org.mockito.ArgumentMatchers.anyString()
                );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.executeAuthorizedTracked(
                        fixture.command(),
                        externalEffects::incrementAndGet
                )
        );

        // Se conserva el error original y se adjunta el de auditoría.
        assertSame(completionFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(auditFailure, failure.getSuppressed()[0]);

        List<AttemptSnapshot> recorded = history(fixture);
        assertEquals(1, recorded.size());

        AttemptSnapshot attempt = recorded.getFirst();

        assertAll(
                () -> assertEquals(1, externalEffects.get()),
                () -> assertEquals(1, attempt.attemptNumber()),
                () -> assertEquals(
                        ExecutionStatus.IN_PROGRESS,
                        attempt.status()
                ),
                () -> assertEquals(
                        EffectOutcome.EFFECT_UNKNOWN,
                        attempt.effectOutcome()
                ),
                () -> assertNotNull(attempt.startedAt()),
                () -> assertNull(attempt.completedAt())
        );

        verify(attempts).completeAttempt(attempt.executionId(), null);
        verify(attempts).markUnknown(
                attempt.executionId(),
                "completion-uncertain:" + attempt.executionId()
        );

        // Ningún método debe permitir repetir el efecto.
        assertThrows(
                ExecutionDeniedException.class,
                () -> coordinator.executeAuthorizedTracked(
                        fixture.command(),
                        externalEffects::incrementAndGet
                )
        );

        assertThrows(
                ExecutionDeniedException.class,
                () -> coordinator.executeAuthorized(
                        fixture.command(),
                        externalEffects::incrementAndGet
                )
        );

        List<AttemptSnapshot> finalHistory = history(fixture);
        assertEquals(1, finalHistory.size());

        assertAll(
                () -> assertEquals(1, externalEffects.get()),
                () -> assertEquals(
                        attempt.executionId(),
                        finalHistory.getFirst().executionId()
                ),
                // Nueva validación
                () -> assertEquals(
                        1,
                        finalHistory.getFirst().attemptNumber()
                ),
                () -> assertEquals(
                        ExecutionStatus.IN_PROGRESS,
                        finalHistory.getFirst().status()
                ),
                () -> assertNull(
                        finalHistory.getFirst().completedAt()
                )
        );
    }
}
