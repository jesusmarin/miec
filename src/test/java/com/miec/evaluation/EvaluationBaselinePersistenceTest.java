
package com.miec.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de la regla IR-003 de MIEC-F03.
 *
 * <p>Una evaluación puede referenciar una línea base
 * únicamente cuando ambas pertenecen al mismo ciclo.
 *
 * <p>La regla es implementada por Flyway V002 mediante:
 * FK_EVALUATION_BASELINE_CYCLE.
 *
 * <p>Se utilizan:
 * <ul>
 *   <li>H2 en memoria, aislada de la base de desarrollo.</li>
 *   <li>Flyway V001 y V002 para construir el esquema.</li>
 *   <li>Spring Boot para inicializar el contexto.</li>
 *   <li>JdbcTemplate para verificar restricciones SQL.</li>
 *   <li>JUnit 5 para ejecutar las comprobaciones.</li>
 * </ul>
 *
 * <p>Las pruebas se ejecutan dentro de transacciones
 * que se revierten al finalizar cada método.
 *
 * <p>Los datos de preparación cumplen las restricciones
 * IR-001 e IR-002, permitiendo aislar IR-003.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir003_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-003 - Integridad evaluación y línea base"
)
class EvaluationBaselinePersistenceTest {

    /** Acceso JDBC a la base H2 de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Representa las referencias necesarias para
     * crear una evaluación y una línea base válidas.
     *
     * @param systemId sistema objetivo
     * @param cycleId ciclo de evolución
     * @param objectiveId objetivo asociado al ciclo
     * @param indicatorId indicador asociado al objetivo
     */
    private record CycleFixture(
            UUID systemId,
            UUID cycleId,
            UUID objectiveId,
            UUID indicatorId
    ) {
    }

    /**
     * Crea un sistema objetivo.
     *
     * @return identificador del sistema creado
     */
    private UUID createSystem() {
        UUID systemId = UUID.randomUUID();

        int rows = jdbcTemplate.update(
                """
                INSERT INTO system_objective (
                    system_id,
                    name
                )
                VALUES (?, ?)
                """,
                systemId,
                "Sistema de validación IR-003"
        );

        assertEquals(1, rows);

        return systemId;
    }

    /**
     * Crea un ciclo asociado a un sistema.
     *
     * @param systemId identificador del sistema
     * @return identificador del ciclo creado
     */
    private UUID createCycle(UUID systemId) {
        UUID cycleId = UUID.randomUUID();

        int rows = jdbcTemplate.update(
                """
                INSERT INTO cycle (
                    cycle_id,
                    system_id
                )
                VALUES (?, ?)
                """,
                cycleId,
                systemId
        );

        assertEquals(1, rows);

        return cycleId;
    }

    /**
     * Crea un objetivo perteneciente a un sistema.
     *
     * @param systemId identificador del sistema
     * @return identificador del objetivo
     */
    private UUID createObjective(UUID systemId) {
        UUID objectiveId = UUID.randomUUID();

        int rows = jdbcTemplate.update(
                """
                INSERT INTO objective (
                    objective_id,
                    system_id,
                    name
                )
                VALUES (?, ?, ?)
                """,
                objectiveId,
                systemId,
                "Mejorar el desempeño del sistema"
        );

        assertEquals(1, rows);

        return objectiveId;
    }

    /**
     * Asocia explícitamente un objetivo con su ciclo.
     *
     * <p>Esta relación satisface las restricciones
     * de pertenencia IR-001 e IR-002 de Flyway V002.
     *
     * @param cycleId identificador del ciclo
     * @param objectiveId identificador del objetivo
     * @param systemId identificador del sistema común
     */
    private void associateObjective(
            UUID cycleId,
            UUID objectiveId,
            UUID systemId
    ) {
        int rows = jdbcTemplate.update(
                """
                INSERT INTO cycle_objective (
                    cycle_id,
                    objective_id,
                    system_id
                )
                VALUES (?, ?, ?)
                """,
                cycleId,
                objectiveId,
                systemId
        );

        assertEquals(1, rows);
    }

    /**
     * Crea un indicador perteneciente a un objetivo.
     *
     * <p>El indicador es necesario porque baseline
     * exige indicator_id.
     *
     * @param objectiveId identificador del objetivo
     * @return identificador del indicador creado
     */
    private UUID createIndicator(UUID objectiveId) {
        UUID indicatorId = UUID.randomUUID();

        int rows = jdbcTemplate.update(
                """
                INSERT INTO indicator (
                    indicator_id,
                    objective_id,
                    name
                )
                VALUES (?, ?, ?)
                """,
                indicatorId,
                objectiveId,
                "Indicador de desempeño"
        );

        assertEquals(1, rows);

        return indicatorId;
    }

    /**
     * Crea un conjunto válido de datos para un ciclo.
     *
     * <p>Incluye sistema, ciclo, objetivo asociado
     * e indicador perteneciente a ese objetivo.
     *
     * @return referencias de los registros creados
     */
    private CycleFixture createCycleFixture() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId);
        UUID objectiveId = createObjective(systemId);

        associateObjective(
                cycleId,
                objectiveId,
                systemId
        );

        UUID indicatorId = createIndicator(objectiveId);

        return new CycleFixture(
                systemId,
                cycleId,
                objectiveId,
                indicatorId
        );
    }

    /**
     * Crea una línea base para el ciclo e indicador.
     *
     * @param cycleId identificador del ciclo
     * @param indicatorId identificador del indicador
     * @return identificador de la línea base
     */
    private UUID createBaseline(
            UUID cycleId,
            UUID indicatorId
    ) {
        UUID baselineId = UUID.randomUUID();

        int rows = jdbcTemplate.update(
                """
                INSERT INTO baseline (
                    baseline_id,
                    cycle_id,
                    indicator_id,
                    observed_value,
                    measured_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                baselineId,
                cycleId,
                indicatorId,
                new BigDecimal("75.00"),
                Timestamp.from(Instant.now())
        );

        assertEquals(1, rows);

        return baselineId;
    }

    /**
     * Inserta una evaluación asociada a un ciclo
     * y objetivo, con una línea base opcional.
     *
     * <p>baselineId puede ser null porque la columna
     * baseline_id es opcional en Flyway V001.
     *
     * @param evaluationId identificador de evaluación
     * @param cycleId identificador del ciclo
     * @param objectiveId objetivo asociado al ciclo
     * @param baselineId línea base o null
     * @return cantidad de filas insertadas
     */
    private int insertEvaluation(
            UUID evaluationId,
            UUID cycleId,
            UUID objectiveId,
            UUID baselineId
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO evaluation (
                    evaluation_id,
                    cycle_id,
                    objective_id,
                    baseline_id,
                    criteria_version_id,
                    result_classification,
                    evidence_sufficiency,
                    confidence_level,
                    evaluated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                evaluationId,
                cycleId,
                objectiveId,
                baselineId,
                "MIEC-CRITERIA-001",
                "SUCCESS",
                "SUFFICIENT",
                "HIGH",
                Timestamp.from(Instant.now())
        );
    }

    /**
     * Cuenta las evaluaciones correspondientes
     * a un identificador determinado.
     *
     * @param evaluationId identificador consultado
     * @return número de evaluaciones encontradas
     */
    private int countEvaluation(UUID evaluationId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation
                WHERE evaluation_id = ?
                """,
                Integer.class,
                evaluationId
        );

        return java.util.Objects.requireNonNull(count);
    }

    /**
     * Comprueba la persistencia de una evaluación
     * cuya línea base pertenece al mismo ciclo.
     */
    @Test
    @DisplayName(
            "Debe aceptar una evaluación con línea base del mismo ciclo"
    )
    void shouldAcceptBaselineFromSameCycle() {
        CycleFixture fixture = createCycleFixture();

        UUID baselineId = createBaseline(
                fixture.cycleId(),
                fixture.indicatorId()
        );

        UUID evaluationId = UUID.randomUUID();

        assertEquals(
                1,
                insertEvaluation(
                        evaluationId,
                        fixture.cycleId(),
                        fixture.objectiveId(),
                        baselineId
                )
        );

        UUID persistedBaselineId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT baseline_id
                        FROM evaluation
                        WHERE evaluation_id = ?
                        """,
                        UUID.class,
                        evaluationId
                );

        assertEquals(baselineId, persistedBaselineId);
        assertEquals(1, countEvaluation(evaluationId));
    }

    /**
     * Comprueba que una evaluación pueda existir
     * sin referencia a una línea base.
     *
     * <p>La ausencia de línea base está permitida
     * por la nulabilidad de baseline_id en V001.
     */
    @Test
    @DisplayName(
            "Debe aceptar una evaluación sin línea base"
    )
    void shouldAcceptEvaluationWithoutBaseline() {
        CycleFixture fixture = createCycleFixture();

        UUID evaluationId = UUID.randomUUID();

        assertEquals(
                1,
                insertEvaluation(
                        evaluationId,
                        fixture.cycleId(),
                        fixture.objectiveId(),
                        null
                )
        );

        assertEquals(1, countEvaluation(evaluationId));

        UUID persistedBaselineId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT baseline_id
                        FROM evaluation
                        WHERE evaluation_id = ?
                        """,
                        UUID.class,
                        evaluationId
                );

        assertNull(persistedBaselineId);
    }

    /**
     * Verifica IR-003 cuando se intenta utilizar
     * una línea base perteneciente a otro ciclo.
     *
     * <p>Ambos ciclos pertenecen al mismo sistema,
     * comparten el objetivo y su indicador.
     *
     * <p>El objetivo está asociado explícitamente
     * a los dos ciclos, por lo que el rechazo debe
     * producirse específicamente por la incompatibilidad
     * entre baseline_id y cycle_id.
     */
    @Test
    @DisplayName(
            "Debe rechazar una línea base perteneciente a otro ciclo"
    )
    void shouldRejectBaselineFromDifferentCycle() {
        CycleFixture fixture = createCycleFixture();

        UUID cycleB = createCycle(fixture.systemId());

        associateObjective(
                cycleB,
                fixture.objectiveId(),
                fixture.systemId()
        );

        UUID baselineB = createBaseline(
                cycleB,
                fixture.indicatorId()
        );

        UUID evaluationId = UUID.randomUUID();

        assertNotEquals(fixture.cycleId(), cycleB);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvaluation(
                        evaluationId,
                        fixture.cycleId(),
                        fixture.objectiveId(),
                        baselineB
                )
        );

        assertEquals(0, countEvaluation(evaluationId));
    }

    /**
     * Verifica que una evaluación no pueda
     * referenciar una línea base inexistente.
     *
     * <p>La evaluación y el objetivo mantienen
     * una asociación válida, aislando la
     * referencia de línea base como causa del error.
     */
    @Test
    @DisplayName(
            "Debe rechazar una evaluación con línea base inexistente"
    )
    void shouldRejectNonexistentBaseline() {
        CycleFixture fixture = createCycleFixture();

        UUID unknownBaselineId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvaluation(
                        evaluationId,
                        fixture.cycleId(),
                        fixture.objectiveId(),
                        unknownBaselineId
                )
        );

        assertEquals(0, countEvaluation(evaluationId));
    }

    /**
     * Comprueba que una línea base pueda
     * utilizarse en varias evaluaciones
     * del mismo ciclo.
     *
     * <p>IR-003 no establece unicidad de
     * baseline_id en la tabla evaluation.
     *
     * <p>Cada evaluación posee su propio UUID
     * y conserva el mismo contexto de ciclo.
     */
    @Test
    @DisplayName(
            "Debe permitir varias evaluaciones del mismo ciclo con una línea base compartida"
    )
    void shouldAllowMultipleEvaluationsWithSameBaseline() {
        CycleFixture fixture = createCycleFixture();

        UUID baselineId = createBaseline(
                fixture.cycleId(),
                fixture.indicatorId()
        );

        UUID firstEvaluationId = UUID.randomUUID();
        UUID secondEvaluationId = UUID.randomUUID();

        assertEquals(
                1,
                insertEvaluation(
                        firstEvaluationId,
                        fixture.cycleId(),
                        fixture.objectiveId(),
                        baselineId
                )
        );

        assertEquals(
                1,
                insertEvaluation(
                        secondEvaluationId,
                        fixture.cycleId(),
                        fixture.objectiveId(),
                        baselineId
                )
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation
                WHERE cycle_id = ?
                  AND baseline_id = ?
                """,
                Integer.class,
                fixture.cycleId(),
                baselineId
        );

        assertEquals(2, count);
    }
}
