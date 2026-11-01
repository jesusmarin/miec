
package com.miec.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de la regla IR-002 de MIEC-F03.
 *
 * <p>Una evaluación debe referenciar un objetivo
 * asociado explícitamente al mismo ciclo.
 *
 * <p>La regla se implementa mediante la clave foránea
 * compuesta FK_EVALUATION_CYCLE_OBJECTIVE de Flyway V002.
 *
 * <p>Las pruebas utilizan JDBC directamente para
 * verificar las restricciones físicas de H2, sin
 * depender de nuevas entidades JPA.
 *
 * <p>Las operaciones se ejecutan dentro de una
 * transacción que se revierte al finalizar cada prueba.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir002_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-002 - Integridad evaluación, ciclo y objetivo"
)
class EvaluationCycleObjectivePersistenceTest {

    /** Cliente JDBC para preparar y verificar los registros. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Crea un sistema objetivo.
     *
     * @return identificador del sistema
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
                "Sistema de validación IR-002"
        );

        assertEquals(1, rows);
        return systemId;
    }

    /**
     * Crea un ciclo perteneciente a un sistema.
     *
     * @param systemId identificador del sistema
     * @return identificador del ciclo
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
                "Objetivo de evolución"
        );

        assertEquals(1, rows);
        return objectiveId;
    }

    /**
     * Asocia explícitamente un objetivo con un ciclo.
     *
     * <p>Se proporciona el identificador común
     * del sistema, conforme al esquema V002 e IR-001.
     *
     * @param cycleId identificador del ciclo
     * @param objectiveId identificador del objetivo
     * @param systemId identificador del sistema
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
     * Intenta insertar una evaluación.
     *
     * <p>La inserción contiene los campos obligatorios
     * de Flyway V001. La compatibilidad entre ciclo
     * y objetivo se delega a Flyway V002.
     *
     * @param evaluationId identificador de evaluación
     * @param cycleId identificador del ciclo
     * @param objectiveId identificador del objetivo
     * @return número de registros insertados
     */
    private int insertEvaluation(
            UUID evaluationId,
            UUID cycleId,
            UUID objectiveId
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO evaluation (
                    evaluation_id,
                    cycle_id,
                    objective_id,
                    criteria_version_id,
                    result_classification,
                    evidence_sufficiency,
                    confidence_level,
                    evaluated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                evaluationId,
                cycleId,
                objectiveId,
                "MIEC-CRITERIA-001",
                "SUCCESS",
                "SUFFICIENT",
                "HIGH",
                Timestamp.from(Instant.now())
        );
    }

    /**
     * Cuenta las evaluaciones que coinciden
     * con un identificador.
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

        return assertDoesNotThrow(
                () -> java.util.Objects.requireNonNull(count)
        );
    }

    /**
     * Comprueba la persistencia de una evaluación
     * cuyo objetivo pertenece al ciclo.
     */
    @Test
    @DisplayName(
            "Debe aceptar una evaluación con objetivo asociado al mismo ciclo"
    )
    void shouldAcceptEvaluationWithAssociatedObjective() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId);
        UUID objectiveId = createObjective(systemId);

        associateObjective(cycleId, objectiveId, systemId);

        UUID evaluationId = UUID.randomUUID();

        assertEquals(
                1,
                insertEvaluation(
                        evaluationId,
                        cycleId,
                        objectiveId
                )
        );

        assertEquals(1, countEvaluation(evaluationId));
    }

    /**
     * Verifica que compartir sistema no sea
     * suficiente para validar una evaluación.
     *
     * <p>El objetivo existe, pero no está asociado
     * al ciclo mediante cycle_objective.
     */
    @Test
    @DisplayName(
            "Debe rechazar un objetivo del sistema no asociado al ciclo"
    )
    void shouldRejectObjectiveNotAssociatedWithCycle() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId);
        UUID objectiveId = createObjective(systemId);
        UUID evaluationId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvaluation(
                        evaluationId,
                        cycleId,
                        objectiveId
                )
        );

        assertEquals(0, countEvaluation(evaluationId));
    }

    /**
     * Verifica el aislamiento de objetivos entre
     * distintos ciclos de un mismo sistema.
     *
     * <p>El objetivo se asocia al ciclo B, pero
     * se intenta evaluarlo dentro del ciclo A.
     */
    @Test
    @DisplayName(
            "Debe rechazar un objetivo asociado solamente a otro ciclo"
    )
    void shouldRejectObjectiveAssociatedWithDifferentCycle() {
        UUID systemId = createSystem();

        UUID cycleA = createCycle(systemId);
        UUID cycleB = createCycle(systemId);
        UUID objectiveId = createObjective(systemId);

        associateObjective(
                cycleB,
                objectiveId,
                systemId
        );

        UUID evaluationId = UUID.randomUUID();

        assertNotEquals(cycleA, cycleB);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvaluation(
                        evaluationId,
                        cycleA,
                        objectiveId
                )
        );

        assertEquals(0, countEvaluation(evaluationId));
    }

    /**
     * Comprueba que tampoco pueda evaluarse
     * un objetivo inexistente.
     */
    @Test
    @DisplayName(
            "Debe rechazar una evaluación con objetivo inexistente"
    )
    void shouldRejectNonexistentObjective() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId);

        UUID unknownObjectiveId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvaluation(
                        evaluationId,
                        cycleId,
                        unknownObjectiveId
                )
        );

        assertEquals(0, countEvaluation(evaluationId));
    }

    /**
     * Comprueba que un objetivo puede ser evaluado
     * en varios ciclos cuando existe una asociación
     * explícita para cada ciclo.
     *
     * <p>La regla IR-002 protege la pertenencia
     * de la asociación, no impide su reutilización.
     */
    @Test
    @DisplayName(
            "Debe permitir evaluar un objetivo en dos ciclos asociados explícitamente"
    )
    void shouldAllowObjectiveInMultipleAssociatedCycles() {
        UUID systemId = createSystem();
        UUID cycleA = createCycle(systemId);
        UUID cycleB = createCycle(systemId);
        UUID objectiveId = createObjective(systemId);

        associateObjective(
                cycleA,
                objectiveId,
                systemId
        );

        associateObjective(
                cycleB,
                objectiveId,
                systemId
        );

        UUID evaluationA = UUID.randomUUID();
        UUID evaluationB = UUID.randomUUID();

        assertEquals(
                1,
                insertEvaluation(
                        evaluationA,
                        cycleA,
                        objectiveId
                )
        );

        assertEquals(
                1,
                insertEvaluation(
                        evaluationB,
                        cycleB,
                        objectiveId
                )
        );

        assertAll(
                () -> assertEquals(
                        1,
                        countEvaluation(evaluationA)
                ),
                () -> assertEquals(
                        1,
                        countEvaluation(evaluationB)
                )
        );
    }
}
