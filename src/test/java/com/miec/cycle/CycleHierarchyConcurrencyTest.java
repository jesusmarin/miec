
package com.miec.cycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de concurrencia para IR-013.
 *
 * <p>Comprueban que las actualizaciones simultáneas
 * de la jerarquía de ciclos no produzcan referencias
 * circulares directas ni indirectas.
 *
 * <p>La prueba no utiliza @Transactional en JUnit:
 * los datos iniciales se confirman antes de iniciar
 * los hilos concurrentes.
 *
 * <p>Cada llamada al servicio se ejecuta en un hilo
 * independiente y utiliza la transacción declarada
 * en CycleHierarchyService.assignParent().
 *
 * <p>La sincronización mediante CountDownLatch
 * aproxima el inicio simultáneo de las solicitudes.
 * No garantiza un orden particular de ejecución.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir013_concurrency_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.datasource.hikari.maximum-pool-size=6"
        }
)
@DisplayName(
        "MIEC-F03 IR-013 - Concurrencia en jerarquías de ciclos"
)
class CycleHierarchyConcurrencyTest {

    /** Servicio transaccional sometido a prueba. */
    @Autowired
    private CycleHierarchyService hierarchyService;

    /** Acceso JDBC a los datos persistidos. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Gestor de transacciones para preparar los datos. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Representa el resultado de un intento concurrente.
     *
     * @param accepted indica si la asignación se confirmó
     * @param reason motivo del rechazo
     */
    private record Attempt(
            boolean accepted,
            String reason
    ) {
    }

    /**
     * Agrupa los resultados de dos operaciones concurrentes.
     *
     * @param first resultado del primer hilo
     * @param second resultado del segundo hilo
     */
    private record RaceResult(
            Attempt first,
            Attempt second
    ) {

        /**
         * Cuenta las asignaciones confirmadas.
         *
         * @return número de operaciones exitosas
         */
        int acceptedCount() {
            return (first.accepted() ? 1 : 0)
                    + (second.accepted() ? 1 : 0);
        }

        /**
         * Determina si ambas denegaciones se debieron
         * a reglas distintas de IR-013.
         *
         * @return true si alguna denegación confirma IR-013
         */
        boolean hasHierarchyCycleRejection() {
            return (!first.accepted()
                    && first.reason().contains("IR-013"))
                    || (!second.accepted()
                    && second.reason().contains("IR-013"));
        }
    }

    /**
     * Crea un sistema con una transacción confirmada.
     *
     * @return identificador persistido
     */
    private UUID createSystem() {
        UUID systemId = UUID.randomUUID();

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertEquals(1, jdbcTemplate.update(
                    """
                    INSERT INTO system_objective (
                        system_id, name
                    )
                    VALUES (?, ?)
                    """,
                    systemId,
                    "Sistema de concurrencia IR-013"
            ));
        });

        return systemId;
    }

    /**
     * Crea un ciclo en una transacción independiente.
     *
     * @param systemId sistema propietario
     * @return identificador del ciclo
     */
    private UUID createCycle(UUID systemId) {
        UUID cycleId = UUID.randomUUID();

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertEquals(1, jdbcTemplate.update(
                    """
                    INSERT INTO cycle (
                        cycle_id, system_id
                    )
                    VALUES (?, ?)
                    """,
                    cycleId,
                    systemId
            ));
        });

        return cycleId;
    }

    /**
     * Consulta el identificador del padre ya confirmado.
     *
     * @param cycleId identificador del ciclo
     * @return padre actual o null
     */
    private UUID findParent(UUID cycleId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT parent_cycle_id
                FROM cycle
                WHERE cycle_id = ?
                """,
                UUID.class,
                cycleId
        );
    }

    /**
     * Consulta la versión persistida de un ciclo.
     *
     * @param cycleId identificador consultado
     * @return versión actual
     */
    private long findVersion(UUID cycleId) {
        Long version = jdbcTemplate.queryForObject(
                """
                SELECT version
                FROM cycle
                WHERE cycle_id = ?
                """,
                Long.class,
                cycleId
        );

        return Objects.requireNonNull(version);
    }

    /**
     * Ejecuta dos operaciones con un punto de
     * arranque compartido.
     *
     * <p>El método:
     * <ol>
     *   <li>Inicia dos hilos diferentes.</li>
     *   <li>Espera a que ambos estén preparados.</li>
     *   <li>Libera simultáneamente las operaciones.</li>
     *   <li>Espera a que terminen ambas transacciones.</li>
     * </ol>
     *
     * <p>Las denegaciones de dominio se convierten
     * en resultados verificables. Las excepciones
     * inesperadas provocan el fallo de la prueba.
     *
     * @param first primera operación
     * @param second segunda operación
     * @return resultados independientes
     * @throws Exception ante error o tiempo agotado
     */
    private RaceResult runConcurrently(
            Runnable first,
            Runnable second
    ) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Callable<Attempt> firstTask =
                    createConcurrentTask(first, ready, start);

            Callable<Attempt> secondTask =
                    createConcurrentTask(second, ready, start);

            Future<Attempt> firstFuture =
                    executor.submit(firstTask);

            Future<Attempt> secondFuture =
                    executor.submit(secondTask);

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Ambos hilos deben estar preparados"
            );

            start.countDown();

            Attempt firstResult =
                    firstFuture.get(15, TimeUnit.SECONDS);

            Attempt secondResult =
                    secondFuture.get(15, TimeUnit.SECONDS);

            return new RaceResult(
                    firstResult,
                    secondResult
            );
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            10,
                            TimeUnit.SECONDS
                    ),
                    "Los hilos deben finalizar"
            );
        }
    }

    /**
     * Construye una operación concurrente.
     *
     * <p>Solo las excepciones IllegalArgumentException
     * se interpretan como denegaciones del dominio.
     * Los fallos de infraestructura se propagan.
     *
     * @param operation asignación que se ejecutará
     * @param ready señal de preparación
     * @param start señal de inicio
     * @return tarea concurrente
     */
    private Callable<Attempt> createConcurrentTask(
            Runnable operation,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();

            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "No se recibió la señal de inicio"
                );
            }

            try {
                operation.run();

                return new Attempt(true, "");
            } catch (IllegalArgumentException exception) {
                return new Attempt(
                        false,
                        exception.getMessage() == null
                                ? ""
                                : exception.getMessage()
                );
            }
        };
    }

    /**
     * Comprueba la prevención de un ciclo de
     * dos nodos bajo transacciones simultáneas.
     *
     * <p>Las operaciones compiten para crear:
     * A.parent = B y B.parent = A.
     *
     * <p>Solo una puede confirmarse.
     */
    @Test
    @DisplayName(
            "Debe impedir un ciclo A-B-A con dos transacciones concurrentes"
    )
    void shouldPreventConcurrentTwoNodeCycle()
            throws Exception {

        UUID systemId = createSystem();

        UUID cycleA = createCycle(systemId);
        UUID cycleB = createCycle(systemId);

        RaceResult result = runConcurrently(
                () -> hierarchyService.assignParent(
                        cycleA, cycleB
                ),
                () -> hierarchyService.assignParent(
                        cycleB, cycleA
                )
        );

        assertEquals(
                1,
                result.acceptedCount(),
                "Solo una transacción puede confirmarse"
        );

        assertTrue(
                result.hasHierarchyCycleRejection(),
                "La segunda operación debe rechazarse por IR-013"
        );

        UUID parentA = findParent(cycleA);
        UUID parentB = findParent(cycleB);

        assertTrue(
                (cycleB.equals(parentA) && parentB == null)
                        || (cycleA.equals(parentB) && parentA == null),
                "La jerarquía confirmada no debe contener ciclos"
        );

        assertEquals(
                1L,
                findVersion(cycleA) + findVersion(cycleB),
                "Solo un ciclo debe actualizar su versión"
        );
    }

    /**
     * Comprueba la prevención de una relación
     * circular indirecta entre tres ciclos.
     *
     * <p>Existe inicialmente B.parent = A.
     *
     * <p>Las solicitudes concurrentes intentan:
     * A.parent = C y C.parent = B.
     *
     * <p>Si ambas se confirmaran, resultaría
     * A -> C -> B -> A.
     */
    @Test
    @DisplayName(
            "Debe impedir un ciclo A-C-B-A bajo concurrencia"
    )
    void shouldPreventConcurrentThreeNodeCycle()
            throws Exception {

        UUID systemId = createSystem();

        UUID cycleA = createCycle(systemId);
        UUID cycleB = createCycle(systemId);
        UUID cycleC = createCycle(systemId);

        hierarchyService.assignParent(cycleB, cycleA);

        RaceResult result = runConcurrently(
                () -> hierarchyService.assignParent(
                        cycleA, cycleC
                ),
                () -> hierarchyService.assignParent(
                        cycleC, cycleB
                )
        );

        assertEquals(
                1,
                result.acceptedCount(),
                "Solo una asignación adicional puede confirmarse"
        );

        assertTrue(
                result.hasHierarchyCycleRejection()
        );

        assertEquals(cycleA, findParent(cycleB));

        UUID parentA = findParent(cycleA);
        UUID parentC = findParent(cycleC);

        assertTrue(
                (cycleC.equals(parentA) && parentC == null)
                        || (parentA == null && cycleB.equals(parentC)),
                "La jerarquía de tres niveles debe permanecer acíclica"
        );

        assertEquals(
                2L,
                findVersion(cycleA)
                        + findVersion(cycleB)
                        + findVersion(cycleC),
                "Solo las dos asignaciones válidas incrementan versión"
        );
    }

    /**
     * Comprueba que la serialización se aplique
     * por sistema objetivo y no globalmente.
     *
     * <p>Dos sistemas independientes pueden
     * aceptar sus propias relaciones jerárquicas.
     */
    @Test
    @DisplayName(
            "Debe permitir modificaciones concurrentes en sistemas diferentes"
    )
    void shouldAllowConcurrentUpdatesAcrossSystems()
            throws Exception {

        UUID systemA = createSystem();
        UUID systemB = createSystem();

        UUID parentA = createCycle(systemA);
        UUID childA = createCycle(systemA);

        UUID parentB = createCycle(systemB);
        UUID childB = createCycle(systemB);

        RaceResult result = runConcurrently(
                () -> hierarchyService.assignParent(
                        childA, parentA
                ),
                () -> hierarchyService.assignParent(
                        childB, parentB
                )
        );

        assertAll(
                () -> assertEquals(2, result.acceptedCount()),
                () -> assertEquals(parentA, findParent(childA)),
                () -> assertEquals(parentB, findParent(childB)),
                () -> assertEquals(1L, findVersion(childA)),
                () -> assertEquals(1L, findVersion(childB))
        );
    }
}

