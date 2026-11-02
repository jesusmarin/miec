
package com.miec.action;

import com.miec.action.AuthorizedActionExecutionService.ExecutionCommand;
import com.miec.action.AuthorizedActionExecutionService.ExecutionDeniedException;
import com.miec.action.ExecutionAttemptService.AttemptSnapshot;
import com.miec.action.ExecutionAttemptService.CreateAttemptCommand;
import com.miec.action.ExecutionRetryPolicy.ExecutionStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;



/**
 * MIEC-F03 IR-009:
 * Pruebas de integración del camino unificado de ejecución.
 *
 * <p>Responsabilidades verificadas:
 * <ul>
 *   <li>Delegación del método heredado.</li>
 *   <li>Persistencia de intentos en execution_attempt.</li>
 *   <li>Registro de resultados COMPLETED y UNKNOWN.</li>
 *   <li>Prevención de ejecuciones duplicadas.</li>
 *   <li>Revalidación de autorizaciones.</li>
 *   <li>Rechazo de transacciones externas activas.</li>
 *   <li>Compatibilidad con ejecuciones históricas.</li>
 *   <li>Exclusión de ejecuciones concurrentes.</li>
 * </ul>
 *
 * <p>No utiliza @Transactional porque el servicio
 * confirma intentos mediante REQUIRES_NEW.
 *
 * <p>Los escenarios utilizan identificadores UUID
 * independientes y datos confirmados antes de ejecutar.
 */
@SpringBootTest(properties = {
        "spring.datasource.url="
                + "jdbc:h2:mem:miec_ir009_unified;"
                + "DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DisplayName(
        "MIEC-F03 IR-009 - Integración unificada de ejecución"
)
class AuthorizedActionUnifiedIntegrationTest {

    /** Tiempo máximo de espera en pruebas concurrentes. */
    private static final int TIMEOUT_SECONDS = 15;

    @Autowired
    private AuthorizedActionExecutionService coordinator;

    @Autowired
    private ExecutionAttemptService attempts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private InterruptedExecutionDetectionService detector;

    /**
     * Referencias persistidas del escenario.
     *
     * @param planId identificador del plan
     * @param cycleId identificador del ciclo
     * @param actionId identificador de la acción
     * @param authorizationId autorización aplicable
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
                    "miec-test"
            );
        }

        /**
         * Construye el comando directo del servicio
         * de persistencia de intentos.
         */
        CreateAttemptCommand attemptCommand() {
            return new CreateAttemptCommand(
                    actionId,
                    planId,
                    cycleId,
                    authorizationId,
                    "PROCESS_EXECUTION",
                    "miec-test"
            );
        }
    }

    /**
     * Crea un escenario de ejecución autorizado.
     *
     * <p>Inserta sistema, ciclo, plan y autorización
     * mediante JDBC sin transacción externa de prueba.
     *
     * <p>Todos los registros quedan confirmados antes
     * de iniciar posibles solicitudes concurrentes.
     *
     * @return escenario persistido
     */
    private Fixture fixture() {
        UUID systemId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();

        assertEquals(
                1,
                jdbc.update(
                        """
                        INSERT INTO system_objective(
                            system_id, name
                        ) VALUES (?, ?)
                        """,
                        systemId,
                        "Sistema IR-009"
                )
        );

        assertEquals(
                1,
                jdbc.update(
                        """
                        INSERT INTO cycle(
                            cycle_id, system_id
                        ) VALUES (?, ?)
                        """,
                        cycleId,
                        systemId
                )
        );

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

        assertEquals(
                1,
                jdbc.update(
                        """
                        INSERT INTO intervention_plan(
                            plan_id, cycle_id, status, actions
                        )
                        VALUES (?, ?, ?, ? FORMAT JSON)
                        """,
                        planId,
                        cycleId,
                        "PROPOSED",
                        actionsJson
                )
        );

        assertEquals(
                1,
                jdbc.update(
                        """
                        INSERT INTO authorizations(
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
                )
        );

        return new Fixture(
                planId,
                cycleId,
                actionId,
                authorizationId
        );
    }

    /**
     * Recupera el historial completo de una acción.
     *
     * <p>Este método permite comprobar estados,
     * identidades y numeración de los intentos.
     *
     * @param fixture escenario consultado
     * @return historial ordenado por número de intento
     */
    private List<AttemptSnapshot> history(Fixture fixture) {
        return attempts.findHistory(
                fixture.planId(),
                fixture.actionId()
        );
    }

    /**
     * Cuenta los intentos persistidos.
     *
     * @param fixture escenario consultado
     * @return cantidad de intentos
     */
    private int attemptCount(Fixture fixture) {
        return history(fixture).size();
    }

    /**
     * Espera una señal de sincronización.
     *
     * <p>Utiliza tiempo límite para evitar
     * bloqueos indefinidos de la prueba.
     *
     * @param latch señal esperada
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

    @Test
    @DisplayName(
            "Debe ejecutar mediante método heredado "
                    + "y registrar en execution_attempt"
    )
    void shouldRouteLegacyMethodToAttempts() {
        Fixture f = fixture();
        AtomicInteger calls = new AtomicInteger();

        var result = coordinator.executeAuthorized(
                f.command(),
                calls::incrementAndGet
        );

        assertEquals("COMPLETED", result.status());
        assertEquals(1, calls.get());
        assertEquals(1, attemptCount(f));

        Integer legacy = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM executed_action
                WHERE action_id = ?
                """,
                Integer.class,
                f.actionId()
        );

        assertEquals(0, legacy);
    }

    @Test
    @DisplayName(
            "Debe registrar COMPLETED con identidad única "
                    + "desde el método trazable"
    )
    void shouldPersistTrackedCompletion() {
        Fixture f = fixture();

        var result = coordinator.executeAuthorizedTracked(
                f.command(),
                () -> {}
        );

        assertAll(
                () -> assertNotNull(result.executionId()),
                () -> assertEquals(1, result.attemptNumber()),
                () -> assertEquals("COMPLETED", result.status()),
                () -> assertEquals(
                        ExecutionStatus.COMPLETED,
                        history(f).getFirst().status()
                )
        );
    }

    @Test
    @DisplayName(
            "Debe registrar UNKNOWN cuando el ejecutor "
                    + "produce una excepción"
    )
    void shouldRecordUnknownAfterExecutorFailure() {
        Fixture f = fixture();

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.executeAuthorizedTracked(
                        f.command(),
                        () -> {
                            throw new IllegalStateException(
                                    "fallo externo"
                            );
                        }
                )
        );

        assertEquals(
                ExecutionStatus.UNKNOWN,
                history(f).getFirst().status()
        );
    }

    @Test
    @DisplayName(
            "Debe impedir ejecutar la misma acción "
                    + "mediante ambos métodos"
    )
    void shouldPreventCrossPathDuplicates() {
        Fixture f = fixture();
        AtomicInteger calls = new AtomicInteger();

        coordinator.executeAuthorizedTracked(
                f.command(),
                calls::incrementAndGet
        );

        assertThrows(
                ExecutionDeniedException.class,
                () -> coordinator.executeAuthorized(
                        f.command(),
                        calls::incrementAndGet
                )
        );

        assertEquals(1, calls.get());
        assertEquals(1, attemptCount(f));
    }

    @Test
    @DisplayName(
            "Debe revalidar la autorización revocada "
                    + "antes del inicio del intento"
    )
    void shouldRejectRevocationBeforeStart() {
        Fixture f = fixture();

        var pending = attempts.createAttempt(
                f.attemptCommand()
        );

        jdbc.update(
                """
                UPDATE authorizations
                SET revoked_at = ?
                WHERE authorization_id = ?
                """,
                Timestamp.from(
                        Instant.now().minusSeconds(1)
                ),
                f.authorizationId()
        );

        assertThrows(
                ExecutionAttemptService.AttemptDeniedException.class,
                () -> attempts.startAuthorizedAttempt(
                        pending.executionId(),
                        "PROCESS_EXECUTION"
                )
        );

        attempts.rejectPendingBeforeExecution(
                pending.executionId(),
                "authorization-revoked"
        );

        assertEquals(
                ExecutionStatus.FAILED,
                history(f).getFirst().status()
        );
    }

    @Test
    @DisplayName(
            "Debe rechazar una transacción externa "
                    + "antes de crear el intento"
    )
    void shouldRejectOuterTransaction() {
        Fixture f = fixture();

        TransactionTemplate outer =
                new TransactionTemplate(transactionManager);

        assertThrows(
                IllegalStateException.class,
                () -> outer.execute(status -> {
                    coordinator.executeAuthorizedTracked(
                            f.command(),
                            () -> {}
                    );
                    return null;
                })
        );

        assertEquals(0, attemptCount(f));
    }

    @Test
    @DisplayName(
            "Debe impedir ejecutar una acción que figura "
                    + "en executed_action histórico"
    )
    void shouldRejectHistoricalLegacyExecution() {
        Fixture f = fixture();

        jdbc.update(
                """
                INSERT INTO executed_action(
                    action_id,
                    plan_id,
                    cycle_id,
                    action_type,
                    execution_status,
                    started_at,
                    completed_at,
                    actor_id,
                    authorization_id
                )
                VALUES (
                    ?, ?, ?, ?,
                    ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    ?, ?
                )
                """,
                f.actionId(),
                f.planId(),
                f.cycleId(),
                "PROCESS_EXECUTION",
                "COMPLETED",
                "miec-test",
                f.authorizationId()
        );

        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> coordinator.executeAuthorizedTracked(
                        f.command(),
                        calls::incrementAndGet
                )
        );

        assertEquals(0, calls.get());
        assertEquals(0, attemptCount(f));
    }

    /**
     * Verifica que dos solicitudes concurrentes
     * no ejecuten dos veces la misma acción.
     *
     * <p>La primera ejecución se mantiene activa
     * mediante CountDownLatch.
     *
     * <p>Mientras está IN_PROGRESS, una segunda
     * solicitud intenta ejecutar la misma acción.
     *
     * <p>Se verifica:
     * <ul>
     *   <li>Rechazo de la segunda solicitud.</li>
     *   <li>Una única invocación del ejecutor.</li>
     *   <li>Un único intento registrado.</li>
     *   <li>Finalización en COMPLETED.</li>
     * </ul>
     */
    @Test
    @DisplayName(
            "Debe impedir dos ejecuciones concurrentes "
                    + "de la misma acción planificada"
    )
    void shouldPreventConcurrentAuthorizedExecutions()
            throws Exception {

        Fixture f = fixture();

        AtomicInteger calls = new AtomicInteger();

        CountDownLatch executionStarted =
                new CountDownLatch(1);

        CountDownLatch releaseExecution =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            // Primera solicitud.
            Future<?> first = executor.submit(() ->
                    coordinator.executeAuthorizedTracked(
                            f.command(),
                            () -> {
                                calls.incrementAndGet();

                                executionStarted.countDown();

                                // Mantener IN_PROGRESS.
                                awaitSignal(releaseExecution);
                            }
                    )
            );

            // Esperar hasta que realmente haya
            // comenzado la primera ejecución.
            assertTrue(
                    executionStarted.await(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "La primera ejecución debe comenzar"
            );

            // El primer intento debe ser visible
            // desde otra transacción.
            List<AttemptSnapshot> running = history(f);

            assertAll(
                    () -> assertEquals(1, running.size()),
                    () -> assertEquals(
                            ExecutionStatus.IN_PROGRESS,
                            running.getFirst().status()
                    ),
                    () -> assertEquals(1, calls.get())
            );

            // Segunda solicitud sobre la misma acción.
            Future<?> second = executor.submit(() ->
                    coordinator.executeAuthorizedTracked(
                            f.command(),
                            calls::incrementAndGet
                    )
            );

            // La segunda solicitud debe ser rechazada
            // sin llegar al ejecutor.
            ExecutionException rejection = assertThrows(
                    ExecutionException.class,
                    () -> second.get(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    )
            );

            assertInstanceOf(
                    ExecutionDeniedException.class,
                    rejection.getCause()
            );

            assertEquals(
                    1,
                    calls.get(),
                    "El ejecutor solo debe invocarse una vez"
            );

            assertEquals(
                    1,
                    attemptCount(f),
                    "Solo debe existir un intento persistido"
            );

            // Liberar la primera ejecución.
            releaseExecution.countDown();

            first.get(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            List<AttemptSnapshot> completed = history(f);

            assertAll(
                    () -> assertEquals(1, calls.get()),
                    () -> assertEquals(1, completed.size()),
                    () -> assertEquals(
                            1,
                            completed.getFirst().attemptNumber()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.COMPLETED,
                            completed.getFirst().status()
                    ),
                    () -> assertNotNull(
                            completed.getFirst().completedAt()
                    )
            );

        } finally {
            // Liberar siempre la operación bloqueada.
            releaseExecution.countDown();

            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "Los hilos deben finalizar"
            );
        }
    }


    /**
     * Declara una segunda acción dentro del mismo plan
     * y crea una autorización independiente para ella.
     *
     * <p>La modificación del contrato JSON se confirma
     * antes de iniciar los hilos concurrentes.
     *
     * @param fixture escenario que contiene la acción A
     * @param secondActionId identidad de la acción B
     * @return autorización independiente para B
     */
    private UUID addSecondAction(
            Fixture fixture,
            UUID secondActionId
    ) {
        UUID secondAuthorizationId = UUID.randomUUID();

        String actionsJson = """
            {
              "schemaVersion": "01",
              "actions": [
                {
                  "actionId": "%s",
                  "actionType": "PROCESS_EXECUTION"
                },
                {
                  "actionId": "%s",
                  "actionType": "PROCESS_EXECUTION"
                }
              ]
            }
            """.formatted(
                fixture.actionId(),
                secondActionId
        );

        assertEquals(
                1,
                jdbc.update(
                        """
                        UPDATE intervention_plan
                        SET actions = ? FORMAT JSON
                        WHERE plan_id = ?
                        """,
                        actionsJson,
                        fixture.planId()
                )
        );

        assertEquals(
                1,
                jdbc.update(
                        """
                        INSERT INTO authorizations(
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
                        secondAuthorizationId,
                        fixture.planId(),
                        secondActionId,
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
                )
        );

        return secondAuthorizationId;
    }

    /**
     * MIEC-F03 IR-009-C05.
     *
     * Comprueba que dos acciones diferentes, declaradas
     * dentro del mismo plan, pueden ejecutarse
     * independientemente.
     *
     * <p>Escenario:
     * <ol>
     *   <li>La acción A comienza y permanece IN_PROGRESS.</li>
     *   <li>La acción B se ejecuta en otro hilo.</li>
     *   <li>B debe terminar antes de liberar A.</li>
     *   <li>A continúa y termina correctamente.</li>
     * </ol>
     *
     * <p>Se utilizan autorizaciones independientes
     * para evitar que una contención de autorización
     * altere la evaluación del bloqueo del plan.
     *
     * <p>No se utiliza Thread.sleep().
     */
    @Test
    @DisplayName(
            "Debe ejecutar dos acciones diferentes del mismo plan "
                    + "sin bloquear la finalización de la segunda"
    )
    void shouldExecuteDifferentActionsOfSamePlanConcurrently()
            throws Exception {

        // 1. Preparar el plan con dos acciones.
        Fixture fixture = fixture();

        UUID secondActionId = UUID.randomUUID();

        UUID secondAuthorizationId = addSecondAction(
                fixture,
                secondActionId
        );

        ExecutionCommand commandA = fixture.command();

        ExecutionCommand commandB = new ExecutionCommand(
                secondActionId,
                fixture.planId(),
                fixture.cycleId(),
                secondAuthorizationId,
                "PROCESS_EXECUTION",
                "miec-test-b"
        );

        AtomicInteger executionsA = new AtomicInteger();
        AtomicInteger executionsB = new AtomicInteger();

        CountDownLatch actionAStarted =
                new CountDownLatch(1);

        CountDownLatch releaseActionA =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            // 2. Iniciar A y mantenerla ejecutándose.
            Future<?> first = executor.submit(() ->
                    coordinator.executeAuthorizedTracked(
                            commandA,
                            () -> {
                                executionsA.incrementAndGet();

                                actionAStarted.countDown();

                                awaitSignal(releaseActionA);
                            }
                    )
            );

            assertTrue(
                    actionAStarted.await(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "La acción A debe comenzar"
            );

            List<AttemptSnapshot> runningA = history(fixture);

            assertAll(
                    () -> assertEquals(1, executionsA.get()),
                    () -> assertEquals(1, runningA.size()),
                    () -> assertEquals(
                            ExecutionStatus.IN_PROGRESS,
                            runningA.getFirst().status()
                    )
            );

            // 3. Ejecutar B mientras A sigue activa.
            Future<?> second = executor.submit(() ->
                    coordinator.executeAuthorizedTracked(
                            commandB,
                            executionsB::incrementAndGet
                    )
            );

            // Este get() debe terminar mientras A permanece
            // bloqueada. Si el servicio mantiene indebidamente
            // un bloqueo transaccional, la prueba fallará.
            second.get(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            // 4. Consultar ambos historiales.
            List<AttemptSnapshot> attemptsA =
                    history(fixture);

            List<AttemptSnapshot> attemptsB =
                    attempts.findHistory(
                            fixture.planId(),
                            secondActionId
                    );

            assertAll(
                    () -> assertEquals(
                            1,
                            executionsA.get()
                    ),
                    () -> assertEquals(
                            1,
                            executionsB.get()
                    ),
                    () -> assertEquals(
                            1,
                            attemptsA.size()
                    ),
                    () -> assertEquals(
                            1,
                            attemptsB.size()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.IN_PROGRESS,
                            attemptsA.getFirst().status(),
                            "A debe seguir ejecutándose"
                    ),
                    () -> assertEquals(
                            ExecutionStatus.COMPLETED,
                            attemptsB.getFirst().status(),
                            "B debe haber terminado"
                    ),
                    () -> assertEquals(
                            1,
                            attemptsA.getFirst().attemptNumber()
                    ),
                    () -> assertEquals(
                            1,
                            attemptsB.getFirst().attemptNumber()
                    ),
                    () -> assertNotEquals(
                            attemptsA.getFirst().executionId(),
                            attemptsB.getFirst().executionId()
                    )
            );

            // 5. Liberar A después de comprobar que B terminó.
            releaseActionA.countDown();

            first.get(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            List<AttemptSnapshot> completedA =
                    history(fixture);

            List<AttemptSnapshot> completedB =
                    attempts.findHistory(
                            fixture.planId(),
                            secondActionId
                    );

            assertAll(
                    () -> assertEquals(
                            ExecutionStatus.COMPLETED,
                            completedA.getFirst().status()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.COMPLETED,
                            completedB.getFirst().status()
                    ),
                    () -> assertEquals(1, executionsA.get()),
                    () -> assertEquals(1, executionsB.get())
            );

        } finally {
            // Garantizar que A nunca quede esperando
            // indefinidamente si falla alguna aserción.
            releaseActionA.countDown();

            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "Los hilos deben finalizar"
            );
        }
    }


    /**
     * MIEC-F03 IR-009-C06.
     *
     * Verifica que un fallo del primer ejecutor durante
     * una solicitud concurrente no habilite reintentos.
     *
     * <p>Secuencia:
     * <ol>
     *   <li>El primer ejecutor comienza y queda bloqueado.</li>
     *   <li>Se confirma el estado IN_PROGRESS.</li>
     *   <li>Una segunda solicitud es rechazada.</li>
     *   <li>Se libera el primer ejecutor, que falla.</li>
     *   <li>El intento queda registrado como UNKNOWN.</li>
     *   <li>Un intento posterior vuelve a ser rechazado.</li>
     * </ol>
     *
     * <p>La excepción del ejecutor no implica ausencia
     * de efectos externos. Por ello no se permite
     * declarar NO_EFFECT_CONFIRMED automáticamente.
     *
     * <p>La prueba utiliza CountDownLatch, sin
     * pausas artificiales mediante Thread.sleep().
     */
    @Test
    @DisplayName(
            "Debe conservar UNKNOWN y bloquear reintentos "
                    + "cuando falla un ejecutor concurrente"
    )
    void shouldBlockRetriesAfterConcurrentExecutorFailure()
            throws Exception {

        // 1. Preparar escenario confirmado.
        Fixture f = fixture();

        AtomicInteger executions = new AtomicInteger();

        CountDownLatch executionStarted =
                new CountDownLatch(1);

        CountDownLatch releaseFailure =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            // 2. Primera solicitud: entra al ejecutor,
            // espera autorización de la prueba y falla.
            Future<?> first = executor.submit(() ->
                    coordinator.executeAuthorizedTracked(
                            f.command(),
                            () -> {
                                executions.incrementAndGet();

                                executionStarted.countDown();

                                awaitSignal(releaseFailure);

                                throw new IllegalStateException(
                                        "IR-009-C06: fallo simulado "
                                                + "del ejecutor"
                                );
                            }
                    )
            );

            // Confirmar que el ejecutor ya comenzó.
            assertTrue(
                    executionStarted.await(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "El primer ejecutor debe iniciar"
            );

            // 3. Verificar que el intento se encuentra
            // persistido como IN_PROGRESS.
            List<AttemptSnapshot> running = history(f);

            assertAll(
                    () -> assertEquals(
                            1,
                            running.size()
                    ),
                    () -> assertEquals(
                            1,
                            running.getFirst().attemptNumber()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.IN_PROGRESS,
                            running.getFirst().status()
                    ),
                    () -> assertEquals(
                            1,
                            executions.get()
                    )
            );

            UUID originalExecutionId =
                    running.getFirst().executionId();

            // 4. Segunda solicitud mientras la primera
            // todavía permanece dentro del ejecutor.
            Future<?> second = executor.submit(() ->
                    coordinator.executeAuthorizedTracked(
                            f.command(),
                            executions::incrementAndGet
                    )
            );

            ExecutionException concurrentRejection =
                    assertThrows(
                            ExecutionException.class,
                            () -> second.get(
                                    TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                            )
                    );

            assertInstanceOf(
                    ExecutionDeniedException.class,
                    concurrentRejection.getCause(),
                    "La segunda ejecución debe ser denegada"
            );

            assertEquals(
                    1,
                    executions.get(),
                    "La segunda solicitud no debe invocar "
                            + "el ejecutor"
            );

            assertEquals(
                    1,
                    history(f).size(),
                    "No debe crearse otro intento"
            );

            // 5. Liberar el primer ejecutor para
            // producir la excepción simulada.
            releaseFailure.countDown();

            ExecutionException executionFailure =
                    assertThrows(
                            ExecutionException.class,
                            () -> first.get(
                                    TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                            )
                    );

            IllegalStateException originalFailure =
                    assertInstanceOf(
                            IllegalStateException.class,
                            executionFailure.getCause()
                    );

            assertTrue(
                    originalFailure.getMessage().contains(
                            "IR-009-C06"
                    )
            );

            // 6. El coordinador debe conservar UNKNOWN.
            List<AttemptSnapshot> failed = history(f);

            assertAll(
                    () -> assertEquals(
                            1,
                            failed.size()
                    ),
                    () -> assertEquals(
                            originalExecutionId,
                            failed.getFirst().executionId()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.UNKNOWN,
                            failed.getFirst().status()
                    ),
                    () -> assertEquals(
                            ExecutionRetryPolicy.EffectOutcome
                                    .EFFECT_UNKNOWN,
                            failed.getFirst().effectOutcome()
                    ),
                    () -> assertNotNull(
                            failed.getFirst().completedAt()
                    ),
                    () -> assertEquals(
                            1,
                            executions.get()
                    )
            );

            // 7. Intentar ejecutar nuevamente después
            // del resultado UNKNOWN.
            ExecutionDeniedException retryDenied =
                    assertThrows(
                            ExecutionDeniedException.class,
                            () -> coordinator.executeAuthorizedTracked(
                                    f.command(),
                                    executions::incrementAndGet
                            )
                    );

            assertTrue(
                    retryDenied.getMessage().contains("IR-009"),
                    "La denegación debe identificar IR-009"
            );

            // 8. Verificar que el método heredado tampoco
            // permita repetir la ejecución.
            assertThrows(
                    ExecutionDeniedException.class,
                    () -> coordinator.executeAuthorized(
                            f.command(),
                            executions::incrementAndGet
                    )
            );

            // 9. Confirmar que el historial se mantiene
            // íntegro después de todos los rechazos.
            List<AttemptSnapshot> finalHistory = history(f);

            assertAll(
                    () -> assertEquals(
                            1,
                            executions.get(),
                            "El ejecutor solo debió invocarse una vez"
                    ),
                    () -> assertEquals(
                            1,
                            finalHistory.size(),
                            "No deben existir intentos adicionales"
                    ),
                    () -> assertEquals(
                            1,
                            finalHistory.getFirst().attemptNumber()
                    ),
                    () -> assertEquals(
                            ExecutionStatus.UNKNOWN,
                            finalHistory.getFirst().status()
                    ),
                    () -> assertEquals(
                            originalExecutionId,
                            finalHistory.getFirst().executionId()
                    )
            );

        } finally {
            // Garantizar liberación del ejecutor,
            // incluso ante errores de aserción.
            releaseFailure.countDown();

            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "Los hilos deben finalizar"
            );
        }
    }


    /**
     * MIEC-F03 IR-009-C08.1.
     *
     * Verifica la detección de intentos potencialmente
     * interrumpidos sin modificar sus estados.
     *
     * <p>Escenario:
     * <ol>
     *   <li>Crear dos intentos IN_PROGRESS independientes.</li>
     *   <li>Establecer una fecha antigua para el primero.</li>
     *   <li>Mantener reciente el segundo.</li>
     *   <li>Consultar candidatos con umbral de 30 minutos.</li>
     *   <li>Confirmar que solo el antiguo aparece.</li>
     *   <li>Verificar que ambos estados permanecen intactos.</li>
     * </ol>
     */
    @Test
    @DisplayName(
            "Debe detectar ejecuciones antiguas IN_PROGRESS "
                    + "sin modificar estados ni habilitar reintentos"
    )
    void shouldDetectStaleExecutionsWithoutChangingState() {

        Fixture oldFixture = fixture();
        Fixture recentFixture = fixture();

        // Crear dos intentos autorizados.
        var oldPending = attempts.createAttempt(
                oldFixture.attemptCommand()
        );

        var recentPending = attempts.createAttempt(
                recentFixture.attemptCommand()
        );

        var oldStarted = attempts.startAuthorizedAttempt(
                oldPending.executionId(),
                "PROCESS_EXECUTION"
        );

        var recentStarted = attempts.startAuthorizedAttempt(
                recentPending.executionId(),
                "PROCESS_EXECUTION"
        );

        // Simular una ejecución iniciada dos horas antes.
        Instant oldStartTime = Instant.now()
                .minus(Duration.ofHours(2));

        assertEquals(
                1,
                jdbc.update(
                        """
                        UPDATE execution_attempt
                        SET started_at = ?, heartbeat_at = ?, lease_expires_at = ?
                        WHERE execution_id = ?
                        """,
                        Timestamp.from(oldStartTime), Timestamp.from(oldStartTime),
                        Timestamp.from(oldStartTime.plusSeconds(30)), oldStarted.executionId()
                )
        );

        // Consultar las ejecuciones con más de 30 minutos.
        var candidates = detector.findCandidates(
                Duration.ofMinutes(30),
                100
        );

        Set<UUID> candidateIds = candidates.stream()
                .map(
                        InterruptedExecutionDetectionService
                                .ReviewCandidate::executionId
                )
                .collect(Collectors.toSet());

        assertAll(
                () -> assertTrue(
                        candidateIds.contains(
                                oldStarted.executionId()
                        ),
                        "La ejecución antigua debe detectarse"
                ),
                () -> assertFalse(
                        candidateIds.contains(
                                recentStarted.executionId()
                        ),
                        "La ejecución reciente no debe detectarse"
                )
        );

        // Verificar que el detector no cambió estados.
        assertAll(
                () -> assertEquals(
                        ExecutionStatus.IN_PROGRESS,
                        history(oldFixture).getFirst().status()
                ),
                () -> assertEquals(
                        ExecutionStatus.IN_PROGRESS,
                        history(recentFixture).getFirst().status()
                ),
                () -> assertEquals(
                        1,
                        history(oldFixture).size()
                ),
                () -> assertEquals(
                        1,
                        history(recentFixture).size()
                )
        );

        // Ninguno de los intentos puede repetirse.
        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> coordinator.executeAuthorizedTracked(
                        oldFixture.command(),
                        executions::incrementAndGet
                )
        );

        assertThrows(
                ExecutionDeniedException.class,
                () -> coordinator.executeAuthorizedTracked(
                        recentFixture.command(),
                        executions::incrementAndGet
                )
        );

        assertEquals(
                0,
                executions.get(),
                "La detección no debe habilitar ejecuciones"
        );
    }

}

