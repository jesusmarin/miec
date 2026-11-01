
package com.miec.decision;

import com.miec.cycle.CycleEntity;
import com.miec.cycle.CycleObjectiveEntity;
import com.miec.objective.ObjectiveEntity;
import com.miec.system.SystemObjectiveEntity;
import jakarta.persistence.EntityManager;
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
 * Pruebas de integración de DecisionRecordEntity.
 *
 * <p>Verifican la integridad referencial IR-006 del modelo
 * MIEC-F03 ENT-11: toda evaluación referenciada por una
 * decisión debe pertenecer al mismo ciclo.
 *
 * <p>El entorno utiliza:
 * <ul>
 *   <li>Spring Boot y Hibernate ORM.</li>
 *   <li>Una base H2 independiente en memoria.</li>
 *   <li>Flyway V001 y V002 para construir el esquema.</li>
 *   <li>JPA para persistir y recuperar decisiones.</li>
 *   <li>JDBC para verificar restricciones SQL.</li>
 * </ul>
 *
 * <p>Las pruebas son transaccionales y sus cambios
 * se revierten al finalizar cada método.
 *
 * <p>La creación de evaluaciones se realiza mediante JDBC
 * porque EvaluationEntity todavía no forma parte de
 * las entidades JPA implementadas en esta etapa.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_decision_record_it;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 ENT-11 - Integración de decisiones e IR-006"
)
class DecisionRecordPersistenceTest {

    /** Administrador JPA para persistencia de entidades. */
    @Autowired
    private EntityManager entityManager;

    /** Acceso JDBC para preparación y comprobación de datos. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Crea un sistema objetivo y lo persiste.
     *
     * @param name nombre del sistema
     * @return entidad persistida con UUID
     */
    private SystemObjectiveEntity createSystem(String name) {
        SystemObjectiveEntity system =
                new SystemObjectiveEntity(name);

        entityManager.persist(system);
        entityManager.flush();

        return system;
    }

    /**
     * Crea un ciclo asociado a un sistema.
     *
     * @param systemId UUID del sistema propietario
     * @return ciclo persistido
     */
    private CycleEntity createCycle(UUID systemId) {
        CycleEntity cycle = new CycleEntity(systemId);

        entityManager.persist(cycle);
        entityManager.flush();

        return cycle;
    }

    /**
     * Crea un objetivo asociado a un sistema.
     *
     * @param systemId UUID del sistema
     * @return objetivo persistido
     */
    private ObjectiveEntity createObjective(UUID systemId) {
        ObjectiveEntity objective = new ObjectiveEntity(
                systemId,
                "Verificar resultados de evolución"
        );

        entityManager.persist(objective);
        entityManager.flush();

        return objective;
    }

    /**
     * Relaciona un objetivo con su ciclo y sistema.
     *
     * <p>Esta relación es obligatoria para la inserción
     * de evaluaciones conforme a IR-002 de Flyway V002.
     *
     * @param cycle ciclo persistido
     * @param objective objetivo persistido
     * @param systemId sistema común
     */
    private void associateObjective(
            CycleEntity cycle,
            ObjectiveEntity objective,
            UUID systemId
    ) {
        CycleObjectiveEntity association =
                new CycleObjectiveEntity(
                        cycle.getCycleId(),
                        objective.getObjectiveId(),
                        systemId
                );

        entityManager.persist(association);
        entityManager.flush();
    }

    /**
     * Crea una evaluación válida mediante JDBC.
     *
     * <p>Se informan los campos obligatorios de V001.
     * La evaluación referencia un objetivo previamente
     * asociado a su ciclo, satisfaciendo IR-002.
     *
     * @param cycleId UUID del ciclo
     * @param objectiveId UUID del objetivo
     * @return UUID de la evaluación insertada
     */
    private UUID createEvaluation(
            UUID cycleId,
            UUID objectiveId
    ) {
        UUID evaluationId = UUID.randomUUID();

        int affectedRows = jdbcTemplate.update(
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

        assertEquals(1, affectedRows);

        return evaluationId;
    }

    /**
     * Prepara un ciclo con un objetivo asociado
     * y una evaluación existente.
     *
     * @param systemName nombre del sistema de prueba
     * @return datos necesarios para las pruebas
     */
    private EvaluationFixture createEvaluationFixture(
            String systemName
    ) {
        SystemObjectiveEntity system =
                createSystem(systemName);

        CycleEntity cycle =
                createCycle(system.getSystemId());

        ObjectiveEntity objective =
                createObjective(system.getSystemId());

        associateObjective(
                cycle,
                objective,
                system.getSystemId()
        );

        UUID evaluationId = createEvaluation(
                cycle.getCycleId(),
                objective.getObjectiveId()
        );

        return new EvaluationFixture(
                cycle.getCycleId(),
                evaluationId
        );
    }

    /**
     * Agrupa las referencias creadas para cada escenario.
     *
     * @param cycleId ciclo de la evaluación
     * @param evaluationId evaluación persistida
     */
    private record EvaluationFixture(
            UUID cycleId,
            UUID evaluationId
    ) {
    }

    /**
     * Verifica que Hibernate pueda persistir una decisión
     * cuya evaluación pertenece al mismo ciclo.
     */
    @Test
    @DisplayName(
            "Debe persistir una decisión asociada a una evaluación del mismo ciclo"
    )
    void shouldPersistDecisionWithSameCycleEvaluation() {
        EvaluationFixture fixture =
                createEvaluationFixture("Sistema decisión válida");

        DecisionRecordEntity decision =
                new DecisionRecordEntity(
                        fixture.cycleId(),
                        "CONTINUE",
                        "PROPOSED"
                ).evaluationId(fixture.evaluationId());

        entityManager.persist(decision);
        entityManager.flush();

        assertNotNull(decision.getDecisionId());

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM decision_record
                WHERE decision_id = ?
                  AND cycle_id = ?
                  AND evaluation_id = ?
                """,
                Integer.class,
                decision.getDecisionId(),
                fixture.cycleId(),
                fixture.evaluationId()
        );

        assertEquals(1, count);
    }

    /**
     * Comprueba que una decisión persistida pueda
     * recuperarse utilizando su identificador.
     */
    @Test
    @DisplayName(
            "Debe recuperar mediante JPA una decisión con su evaluación asociada"
    )
    void shouldRecoverDecisionWithEvaluation() {
        EvaluationFixture fixture =
                createEvaluationFixture("Sistema recuperación");

        DecisionRecordEntity decision =
                new DecisionRecordEntity(
                        fixture.cycleId(),
                        "ADJUST",
                        "PROPOSED"
                )
                        .evaluationId(fixture.evaluationId())
                        .recommendation("ADJUST");

        entityManager.persist(decision);
        entityManager.flush();

        UUID decisionId = decision.getDecisionId();

        entityManager.clear();

        DecisionRecordEntity recovered =
                entityManager.find(
                        DecisionRecordEntity.class,
                        decisionId
                );

        assertNotNull(recovered);
        assertEquals(
                fixture.cycleId(),
                recovered.getCycleId()
        );
        assertEquals(
                fixture.evaluationId(),
                recovered.getEvaluationId()
        );
        assertEquals(
                "ADJUST",
                recovered.getRecommendation()
        );
    }

    /**
     * Comprueba que evaluation_id pueda ser nulo
     * sin vulnerar la integridad referencial.
     */
    @Test
    @DisplayName(
            "Debe permitir persistir decisiones sin evaluación asociada"
    )
    void shouldPersistDecisionWithoutEvaluation() {
        SystemObjectiveEntity system =
                createSystem("Sistema sin evaluación");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        DecisionRecordEntity decision =
                new DecisionRecordEntity(
                        cycle.getCycleId(),
                        "SUSPEND",
                        "PROPOSED"
                );

        entityManager.persist(decision);
        entityManager.flush();
        entityManager.clear();

        DecisionRecordEntity recovered =
                entityManager.find(
                        DecisionRecordEntity.class,
                        decision.getDecisionId()
                );

        assertNotNull(recovered);
        assertNull(recovered.getEvaluationId());
        assertEquals(
                cycle.getCycleId(),
                recovered.getCycleId()
        );
    }

    /**
     * Verifica IR-006 al intentar vincular una decisión
     * del ciclo A con una evaluación del ciclo B.
     *
     * <p>Se utiliza JDBC para provocar directamente
     * la infracción de la clave foránea compuesta
     * FK_DECISION_EVALUATION_CYCLE.
     */
    @Test
    @DisplayName(
            "Debe rechazar una decisión con evaluación perteneciente a otro ciclo"
    )
    void shouldRejectEvaluationFromDifferentCycle() {
        EvaluationFixture fixtureA =
                createEvaluationFixture("Sistema A");

        EvaluationFixture fixtureB =
                createEvaluationFixture("Sistema B");

        assertNotEquals(
                fixtureA.cycleId(),
                fixtureB.cycleId()
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO decision_record (
                            decision_id,
                            cycle_id,
                            evaluation_id,
                            decision_type,
                            decision_status
                        )
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        fixtureA.cycleId(),
                        fixtureB.evaluationId(),
                        "CONTINUE",
                        "PROPOSED"
                )
        );
    }

    /**
     * Verifica que una decisión no pueda referenciar
     * una evaluación inexistente.
     *
     * <p>Se prueba la restricción referencial tanto
     * para impedir referencias inexistentes como para
     * complementar la validación de IR-006.
     */
    @Test
    @DisplayName(
            "Debe rechazar una decisión que referencia una evaluación inexistente"
    )
    void shouldRejectNonexistentEvaluation() {
        SystemObjectiveEntity system =
                createSystem("Sistema evaluación inexistente");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        UUID unknownEvaluationId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO decision_record (
                            decision_id,
                            cycle_id,
                            evaluation_id,
                            decision_type,
                            decision_status
                        )
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        cycle.getCycleId(),
                        unknownEvaluationId,
                        "CONTINUE",
                        "PROPOSED"
                )
        );
    }
}

