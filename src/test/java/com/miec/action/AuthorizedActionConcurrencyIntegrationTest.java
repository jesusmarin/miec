
package com.miec.action;

import com.miec.action.AuthorizedActionExecutionService.ExecutionCommand;
import com.miec.action.AuthorizedActionExecutionService.ExecutionDeniedException;
import com.miec.action.ExecutionRetryPolicy.ExecutionStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MIEC-F03 IR-009-C03.
 *
 * <p>Pruebas de concurrencia sobre el coordinador
 * único de ejecución autorizada.
 *
 * <p>Valida:
 * <ul>
 *   <li>Exclusión de ejecuciones simultáneas.</li>
 *   <li>Integridad del historial de intentos.</li>
 *   <li>Rechazo de solicitudes duplicadas.</li>
 *   <li>Ausencia de ejecuciones externas duplicadas.</li>
 * </ul>
 *
 * <p>No utiliza @Transactional porque el servicio
 * registra intentos mediante REQUIRES_NEW.
 *
 * <p>Cada prueba utiliza identificadores UUID
 * independientes y datos previamente confirmados.
 */
@SpringBootTest(properties = {
        "spring.datasource.url="
                + "jdbc:h2:mem:miec_ir009_concurrency;"
                + "DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DisplayName(
        "MIEC-F03 IR-009-C03 - Concurrencia de ejecuciones"
)
class AuthorizedActionConcurrencyIntegrationTest {

    private static final int TIMEOUT_SECONDS = 15;

    @Autowired
    private AuthorizedActionExecutionService executionService;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Identificadores del escenario persistido.
     */
    private record Fixture(
            UUID planId,
            UUID cycleId,
            UUID actionId,
            UUID authorizationId
    ) {
    }

    /**
     * Crea un escenario completo de ejecución.
     *
     * <p>Los registros se confirman mediante JDBC
     * antes de iniciar los hilos concurrentes.
     */
    private Fixture createFixture() {
        UUID systemId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();

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
                INSERT INTO system_objective (
                    system_id, name
                ) VALUES (?, ?)
                """,
                systemId,
                "MIEC IR-009 concurrencia"
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

        assertEquals(1, jdbc.update(
                """
                INSERT INTO intervention_plan (
                    plan_id, cycle_id, status, actions
                ) VALUES (?, ?, ?, ? FORMAT JSON)
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                authorizationId,
                planId,
                actionId,
                "miec-requester",
                "human-approver",
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
     * Construye un comando para el escenario.
     */
    private ExecutionCommand command(Fixture fixture) {
        return new ExecutionCommand(
                fixture.actionId(),
                fixture.planId(),
                fixture.cycleId(),
                fixture.authorizationId(),
                "PROCESS_EXECUTION",
                "miec-concurrency-test"
        );
    }

    /**
     * Consulta el historial persistido.
     */
    private List<String> attemptStatuses(Fixture fixture) {
        return jdbc.queryForList(
                """
                SELECT execution_status
                FROM execution_attempt
                WHERE plan_id = ?
                  AND planned_action_id = ?
                ORDER BY attempt_number
                """,
                String.class,
                fixture.planId(),
                fixture.actionId()
        );
    }

    /**
     * Espera una señal de sincronización con tiempo límite.
     *
     * <p>Si la señal no llega, la prueba falla sin
     * quedar bloqueada indefinidamente.
     */
    private static void awaitSignal(CountDownLatch latch) {
        try {
            if (!latch.await(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "IR-009: tiempo de espera agotado"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "IR-009: espera interrumpida",
                    exception
            );
        }
    }

    /**
     * Comprueba que una segunda solicitud sea rechazada
     * mientras la primera se encuentra ejecutándose.
     *
     * <p>El primer ejecutor se mantiene dentro de su
     * operación mediante CountDownLatch.
     *
     * <p>Durante ese intervalo, otro hilo solicita
     * ejecutar exactamente la misma acción.
     *
     * <p>Resultado esperado:
     * <ul>
     *   <li>La segunda solicitud es rechazada.</li>
     *   <li>El ejecutor se invoca una sola vez.</li>
     *   <li>Existe un único intento persistido.</li>
     *   <li>El resultado final es COMPLETED.</li>
     * </ul>
     */
    @Test
    @DisplayName(
            "Debe impedir una segunda ejecución mientras "
                    + "la misma acción está IN_PROGRESS"
    )
    void shouldRejectConcurrentExecutionWhileInProgress()
            throws Exception {

        Fixture fixture = createFixture();

        AtomicInteger executions = new AtomicInteger();

        CountDownLatch executionStarted =
                new CountDownLatch(1);

        CountDownLatch allowCompletion =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        try {
            Future<?> firstExecution = executor.submit(() ->
                    executionService.executeAuthorizedTracked(
                            command(fixture),
                            () -> {
                                executions.incrementAndGet();

                                executionStarted.countDown();

                                awaitSignal(allowCompletion);
                            }
                    )
            );

            // Confirmar que el primer ejecutor comenzó.
            assertTrue(
                    executionStarted.await(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "La primera ejecución debe comenzar"
            );

            // Verificar el estado persistido mientras
            // el ejecutor todavía está activo.
            assertEquals(
                    List.of(ExecutionStatus.IN_PROGRESS.name()),
                    attemptStatuses(fixture)
            );

            // La segunda solicitud no debe llegar al ejecutor.
            ExecutionDeniedException denied = assertThrows(
                    ExecutionDeniedException.class,
                    () -> executionService.executeAuthorizedTracked(
                            command(fixture),
                            executions::incrementAndGet
                    )
            );

            assertTrue(
                    denied.getMessage().contains("IR-009")
            );

            assertEquals(1, executions.get());

            assertEquals(
                    List.of(ExecutionStatus.IN_PROGRESS.name()),
                    attemptStatuses(fixture)
            );

            // Permitir que la primera ejecución termine.
            allowCompletion.countDown();

            firstExecution.get(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            assertAll(
                    () -> assertEquals(1, executions.get()),
                    () -> assertEquals(
                            List.of(ExecutionStatus.COMPLETED.name()),
                            attemptStatuses(fixture)
                    )
            );

        } finally {
            allowCompletion.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "El hilo de prueba debe finalizar"
            );
        }
    }

    /**
     * Comprueba la exclusión cuando dos solicitudes
     * comienzan lo más simultáneamente posible.
     *
     * <p>Se utiliza una barrera de inicio compartida.
     * La prueba no depende del orden en que el
     * planificador del sistema ejecute los hilos.
     *
     * <p>Solo una solicitud debe completar la acción;
     * la otra debe recibir una denegación de dominio.
     */
    @Test
    @DisplayName(
            "Debe aceptar una sola ejecución ante "
                    + "dos solicitudes concurrentes"
    )
    void shouldAllowOnlyOneOfTwoConcurrentRequests()
            throws Exception {

        Fixture fixture = createFixture();

        AtomicInteger executions = new AtomicInteger();

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Callable<Boolean> operation = () -> {
            ready.countDown();
            awaitSignal(start);

            try {
                executionService.executeAuthorizedTracked(
                        command(fixture),
                        executions::incrementAndGet
                );

                return true;

            } catch (ExecutionDeniedException denied) {
                return false;
            }
        };

        try {
            Future<Boolean> first =
                    executor.submit(operation);

            Future<Boolean> second =
                    executor.submit(operation);

            assertTrue(
                    ready.await(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "Ambas solicitudes deben estar preparadas"
            );

            // Liberar ambas solicitudes.
            start.countDown();

            boolean firstAccepted = first.get(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            boolean secondAccepted = second.get(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            int accepted =
                    (firstAccepted ? 1 : 0)
                            + (secondAccepted ? 1 : 0);

            assertAll(
                    () -> assertEquals(
                            1,
                            accepted,
                            "Solo una solicitud debe ser aceptada"
                    ),
                    () -> assertEquals(
                            1,
                            executions.get(),
                            "El ejecutor solo debe invocarse una vez"
                    ),
                    () -> assertEquals(
                            List.of(ExecutionStatus.COMPLETED.name()),
                            attemptStatuses(fixture),
                            "Debe existir un único intento completado"
                    )
            );

        } finally {
            start.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "Los hilos de prueba deben finalizar"
            );
        }
    }
}

