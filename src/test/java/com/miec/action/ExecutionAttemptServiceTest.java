
package com.miec.action;

import com.miec.action.ExecutionAttemptService.AttemptDeniedException;
import com.miec.action.ExecutionAttemptService.AttemptSnapshot;
import com.miec.action.ExecutionAttemptService.CreateAttemptCommand;
import com.miec.action.ExecutionRetryPolicy.EffectOutcome;
import com.miec.action.ExecutionRetryPolicy.ExecutionStatus;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.miec.action.AuthorizedActionExecutionService.ExecutionCommand;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración del servicio de intentos IR-009.
 *
 * <p>Verifican persistencia real mediante V003,
 * numeración, estados y restricciones de reintento.
 *
 * <p>No se utiliza @Transactional en las pruebas,
 * porque el servicio confirma cada operación
 * mediante transacciones REQUIRES_NEW.
 *
 * <p>Los identificadores aleatorios mantienen
 * independientes los escenarios de prueba.
 */
@SpringBootTest(properties = {
        "spring.datasource.url="
                + "jdbc:h2:mem:miec_ir009_attempt_service;"
                + "DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DisplayName(
        "MIEC-F03 IR-009 - Servicio de intentos de ejecución"
)
class ExecutionAttemptServiceTest {

    @Autowired
    private AuthorizedActionExecutionService executionService;

    @Autowired
    private ExecutionAttemptService service;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Referencias persistidas para un escenario.
     */
    private record Fixture(
            UUID planId,
            UUID cycleId,
            UUID actionId,
            UUID authorizationId
    ) {
    }

    /**
     * Crea un escenario válido y confirmado.
     *
     * <p>La acción está declarada en el JSON v01
     * y su autorización se encuentra vigente.
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
                "Sistema de pruebas IR-009"
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
     * Construye un comando válido para el escenario.
     */
    private CreateAttemptCommand command(Fixture fixture) {
        return new CreateAttemptCommand(
                fixture.actionId(),
                fixture.planId(),
                fixture.cycleId(),
                fixture.authorizationId(),
                "PROCESS_EXECUTION",
                "miec-actor"
        );
    }

    /**
     * Recupera el historial de una acción.
     */
    private List<AttemptSnapshot> history(Fixture fixture) {
        return service.findHistory(
                fixture.planId(),
                fixture.actionId()
        );
    }

    @Test
    @DisplayName(
            "Debe crear y persistir el primer intento como PENDING"
    )
    void shouldCreateFirstPendingAttempt() {
        Fixture fixture = createFixture();

        AttemptSnapshot attempt =
                service.createAttempt(command(fixture));

        assertAll(
                () -> assertNotNull(attempt.executionId()),
                () -> assertEquals(1, attempt.attemptNumber()),
                () -> assertEquals(
                        ExecutionStatus.PENDING,
                        attempt.status()
                ),
                () -> assertEquals(1, history(fixture).size())
        );
    }

    @Test
    @DisplayName(
            "Debe impedir crear otro intento mientras existe uno pendiente"
    )
    void shouldRejectAttemptWhilePending() {
        Fixture fixture = createFixture();

        service.createAttempt(command(fixture));

        assertThrows(
                AttemptDeniedException.class,
                () -> service.createAttempt(command(fixture))
        );

        assertEquals(1, history(fixture).size());
    }

    @Test
    @DisplayName(
            "Debe iniciar y completar un intento conservando su resultado"
    )
    void shouldCompleteAttempt() {
        Fixture fixture = createFixture();

        AttemptSnapshot attempt =
                service.createAttempt(command(fixture));

        AttemptSnapshot started =
                service.startAttempt(attempt.executionId());

        AttemptSnapshot completed =
                service.completeAttempt(
                        attempt.executionId(),
                        "result:001"
                );

        assertAll(
                () -> assertEquals(
                        ExecutionStatus.IN_PROGRESS,
                        started.status()
                ),
                () -> assertNotNull(started.startedAt()),
                () -> assertEquals(
                        ExecutionStatus.COMPLETED,
                        completed.status()
                ),
                () -> assertEquals(
                        EffectOutcome.EFFECT_APPLIED,
                        completed.effectOutcome()
                ),
                () -> assertEquals(
                        "result:001",
                        completed.resultReference()
                ),
                () -> assertNotNull(completed.completedAt())
        );

        assertEquals(1, history(fixture).size());

        assertThrows(
                AttemptDeniedException.class,
                () -> service.createAttempt(command(fixture))
        );
    }

    @Test
    @DisplayName(
            "Debe permitir un segundo intento tras un fallo sin efectos"
    )
    void shouldAllowSafeRetry() {
        Fixture fixture = createFixture();

        AttemptSnapshot first =
                service.createAttempt(command(fixture));

        service.startAttempt(first.executionId());

        service.failAttempt(
                first.executionId(),
                EffectOutcome.NO_EFFECT_CONFIRMED,
                "error:001"
        );

        AttemptSnapshot second =
                service.createAttempt(command(fixture));

        assertAll(
                () -> assertEquals(1, first.attemptNumber()),
                () -> assertEquals(2, second.attemptNumber()),
                () -> assertNotEquals(
                        first.executionId(),
                        second.executionId()
                ),
                () -> assertEquals(
                        ExecutionStatus.PENDING,
                        second.status()
                ),
                () -> assertEquals(2, history(fixture).size())
        );
    }

    @Test
    @DisplayName(
            "Debe impedir reintentar un intento de resultado desconocido"
    )
    void shouldRejectUnknownResult() {
        Fixture fixture = createFixture();

        AttemptSnapshot first =
                service.createAttempt(command(fixture));

        service.startAttempt(first.executionId());

        service.markUnknown(
                first.executionId(),
                "timeout:001"
        );

        assertThrows(
                AttemptDeniedException.class,
                () -> service.createAttempt(command(fixture))
        );

        assertEquals(1, history(fixture).size());
    }

    @Test
    @DisplayName(
            "Debe rechazar un nuevo intento sin autorización válida"
    )
    void shouldRejectInvalidAuthorization() {
        Fixture fixture = createFixture();

        jdbc.update(
                """
                UPDATE authorizations
                SET revoked_at = ?
                WHERE authorization_id = ?
                """,
                Timestamp.from(
                        Instant.now().minusSeconds(10)
                ),
                fixture.authorizationId()
        );

        assertThrows(
                AttemptDeniedException.class,
                () -> service.createAttempt(command(fixture))
        );

        assertTrue(history(fixture).isEmpty());
    }

    @Test
    @DisplayName(
            "Debe impedir superar el máximo de tres intentos"
    )
    void shouldEnforceRetryLimit() {
        Fixture fixture = createFixture();

        for (int number = 1; number <= 3; number++) {
            AttemptSnapshot attempt =
                    service.createAttempt(command(fixture));

            assertEquals(
                    number,
                    attempt.attemptNumber()
            );

            service.startAttempt(attempt.executionId());

            service.failAttempt(
                    attempt.executionId(),
                    EffectOutcome.NO_EFFECT_CONFIRMED,
                    "error:" + number
            );
        }

        assertThrows(
                AttemptDeniedException.class,
                () -> service.createAttempt(command(fixture))
        );

        assertEquals(3, history(fixture).size());
    }

    /**
     * Convierte un escenario de intentos en una solicitud
     * para la ejecución autorizada con trazabilidad.
     */
    private ExecutionCommand executionCommand(Fixture fixture) {
        return new ExecutionCommand(
                fixture.actionId(),
                fixture.planId(),
                fixture.cycleId(),
                fixture.authorizationId(),
                "PROCESS_EXECUTION",
                "miec-actor"
        );
    }

    @Test
    @DisplayName(
            "Debe ejecutar una acción y registrar su intento COMPLETED"
    )
    void shouldExecuteAndTrackCompletion() {
        Fixture fixture = createFixture();
        AtomicInteger executions = new AtomicInteger();

        var result = executionService.executeAuthorizedTracked(
                executionCommand(fixture),
                executions::incrementAndGet
        );

        var attempts = history(fixture);

        assertAll(
                () -> assertEquals(1, executions.get()),
                () -> assertEquals("COMPLETED", result.status()),
                () -> assertEquals(1, result.attemptNumber()),
                () -> assertEquals(1, attempts.size()),
                () -> assertEquals(
                        result.executionId(),
                        attempts.getFirst().executionId()
                ),
                () -> assertEquals(
                        ExecutionStatus.COMPLETED,
                        attempts.getFirst().status()
                )
        );
    }

    @Test
    @DisplayName(
            "Debe conservar UNKNOWN cuando el ejecutor falla"
    )
    void shouldPersistUnknownOnExecutionFailure() {
        Fixture fixture = createFixture();

        assertThrows(
                IllegalStateException.class,
                () -> executionService.executeAuthorizedTracked(
                        executionCommand(fixture),
                        () -> {
                            throw new IllegalStateException(
                                    "Fallo del ejecutor"
                            );
                        }
                )
        );

        var attempts = history(fixture);

        assertAll(
                () -> assertEquals(1, attempts.size()),
                () -> assertEquals(
                        ExecutionStatus.UNKNOWN,
                        attempts.getFirst().status()
                ),
                () -> assertEquals(
                        EffectOutcome.EFFECT_UNKNOWN,
                        attempts.getFirst().effectOutcome()
                )
        );
    }

    @Test
    @DisplayName(
            "Debe impedir repetir una ejecución con resultado incierto"
    )
    void shouldRejectRetryAfterUnknownExecution() {
        Fixture fixture = createFixture();
        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () -> executionService.executeAuthorizedTracked(
                        executionCommand(fixture),
                        () -> {
                            executions.incrementAndGet();
                            throw new IllegalStateException(
                                    "Resultado incierto"
                            );
                        }
                )
        );

        assertThrows(
                AuthorizedActionExecutionService.ExecutionDeniedException.class,
                () -> executionService.executeAuthorizedTracked(
                        executionCommand(fixture),
                        executions::incrementAndGet
                )
        );

        assertEquals(1, executions.get());
        assertEquals(1, history(fixture).size());
    }

    @Test
    @DisplayName(
            "Debe impedir repetir una ejecución completada"
    )
    void shouldRejectDuplicateCompletedExecution() {
        Fixture fixture = createFixture();
        AtomicInteger executions = new AtomicInteger();

        executionService.executeAuthorizedTracked(
                executionCommand(fixture),
                executions::incrementAndGet
        );

        assertThrows(
                AuthorizedActionExecutionService.ExecutionDeniedException.class,
                () -> executionService.executeAuthorizedTracked(
                        executionCommand(fixture),
                        executions::incrementAndGet
                )
        );

        assertEquals(1, executions.get());
        assertEquals(1, history(fixture).size());
    }


    /**
     * MIEC-F03 IR-009-C03.
     *
     * Verifica que dos solicitudes concurrentes de creación
     * de intento sobre una misma acción no generen duplicados.
     *
     * <p>Los dos hilos se sincronizan mediante CountDownLatch.
     * El plan y su autorización están confirmados antes de
     * comenzar la ejecución concurrente.
     *
     * <p>Resultado esperado:
     * <ul>
     *   <li>Un intento creado correctamente.</li>
     *   <li>Un rechazo mediante AttemptDeniedException.</li>
     *   <li>Una sola fila persistida.</li>
     *   <li>El número del intento es 1.</li>
     * </ul>
     */
    @Test
    @DisplayName(
            "Debe permitir un único intento cuando dos solicitudes "
                    + "concurrentes crean la misma acción"
    )
    void shouldPreventConcurrentAttemptCreation() throws Exception {

        Fixture fixture = createFixture();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<AttemptSnapshot> task = () -> {
            ready.countDown();

            if (!start.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timeout esperando inicio concurrente"
                );
            }

            return service.createAttempt(command(fixture));
        };

        try {
            Future<AttemptSnapshot> first = executor.submit(task);
            Future<AttemptSnapshot> second = executor.submit(task);

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "Ambos hilos deben estar preparados"
            );

            start.countDown();

            int successful = 0;
            int rejected = 0;

            for (Future<AttemptSnapshot> future :
                    List.of(first, second)) {

                try {
                    AttemptSnapshot attempt =
                            future.get(15, TimeUnit.SECONDS);

                    assertNotNull(attempt.executionId());
                    assertEquals(1, attempt.attemptNumber());
                    assertEquals(
                            ExecutionStatus.PENDING,
                            attempt.status()
                    );

                    successful++;

                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();

                    if (cause instanceof
                            AttemptDeniedException) {
                        rejected++;
                    } else {
                        fail(
                                "Fallo inesperado en concurrencia",
                                cause
                        );
                    }
                }
            }

            assertEquals(
                    1,
                    successful,
                    "Solo una solicitud debe crear un intento"
            );

            assertEquals(
                    1,
                    rejected,
                    "La segunda solicitud debe ser rechazada"
            );

            List<AttemptSnapshot> attempts = history(fixture);

            assertAll(
                    () -> assertEquals(1, attempts.size()),
                    () -> assertEquals(
                            1,
                            attempts.getFirst().attemptNumber()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.PENDING,
                            attempts.getFirst().status()
                    )
            );

        } finally {
            start.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(15, TimeUnit.SECONDS),
                    "Los hilos deben finalizar"
            );
        }
    }


    /**
     * MIEC-F03 IR-009-C04.
     *
     * Comprueba que dos solicitudes concurrentes de
     * reintento, tras un fallo sin efectos confirmados,
     * produzcan exactamente un segundo intento.
     *
     * <p>El intento 1 finaliza FAILED con
     * NO_EFFECT_CONFIRMED.
     *
     * <p>Dos hilos compiten para crear el intento 2.
     * El bloqueo del plan y la restricción única de V003
     * deben impedir numeraciones duplicadas.
     */
    @Test
    @DisplayName(
            "Debe permitir un único reintento número 2 "
                    + "ante solicitudes concurrentes"
    )
    void shouldAllowOnlyOneConcurrentSafeRetry() throws Exception {

        Fixture fixture = createFixture();

        // 1. Crear y finalizar el primer intento.
        AttemptSnapshot first =
                service.createAttempt(command(fixture));

        service.startAttempt(first.executionId());

        service.failAttempt(
                first.executionId(),
                EffectOutcome.NO_EFFECT_CONFIRMED,
                "error:retriable-001"
        );

        assertEquals(
                ExecutionStatus.FAILED,
                history(fixture).getFirst().status()
        );

        // 2. Preparar las solicitudes concurrentes.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Callable<AttemptSnapshot> retryTask = () -> {
            ready.countDown();

            if (!start.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timeout esperando inicio concurrente"
                );
            }

            return service.createAttempt(command(fixture));
        };

        try {
            Future<AttemptSnapshot> firstRetry =
                    executor.submit(retryTask);

            Future<AttemptSnapshot> secondRetry =
                    executor.submit(retryTask);

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "Ambos hilos deben estar preparados"
            );

            start.countDown();

            int created = 0;
            int rejected = 0;

            for (Future<AttemptSnapshot> future :
                    List.of(firstRetry, secondRetry)) {

                try {
                    AttemptSnapshot attempt =
                            future.get(15, TimeUnit.SECONDS);

                    assertEquals(
                            2,
                            attempt.attemptNumber()
                    );

                    assertEquals(
                            ExecutionStatus.PENDING,
                            attempt.status()
                    );

                    created++;

                } catch (ExecutionException exception) {

                    if (exception.getCause()
                            instanceof AttemptDeniedException) {
                        rejected++;
                    } else {
                        fail(
                                "Fallo concurrente inesperado",
                                exception.getCause()
                        );
                    }
                }
            }

            assertEquals(1, created);
            assertEquals(1, rejected);

            // 3. Verificar el historial persistente.
            List<AttemptSnapshot> attempts =
                    history(fixture);

            assertAll(
                    () -> assertEquals(2, attempts.size()),
                    () -> assertEquals(
                            1,
                            attempts.get(0).attemptNumber()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.FAILED,
                            attempts.get(0).status()
                    ),
                    () -> assertEquals(
                            2,
                            attempts.get(1).attemptNumber()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.PENDING,
                            attempts.get(1).status()
                    ),
                    () -> assertNotEquals(
                            attempts.get(0).executionId(),
                            attempts.get(1).executionId()
                    )
            );

        } finally {
            start.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(15, TimeUnit.SECONDS),
                    "Los hilos deben finalizar correctamente"
            );
        }
    }


}
