
package com.miec.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de IR-004 para MIEC-F03.
 *
 * <p>Una evidencia puede utilizarse en evaluaciones
 * pertenecientes a ciclos diferentes del ciclo
 * donde fue originalmente capturada.
 *
 * <p>La reutilización debe conservar:
 * <ul>
 *   <li>El identificador único de la evidencia.</li>
 *   <li>El ciclo donde se capturó originalmente.</li>
 *   <li>La información de procedencia.</li>
 *   <li>La integridad de las referencias.</li>
 * </ul>
 *
 * <p>Las tablas evidence, evaluation y
 * evaluation_evidence se definen en Flyway V001.
 * V002 no restringe la reutilización entre ciclos.
 *
 * <p>Las pruebas utilizan H2 aislada y JDBC.
 * No se crean entidades JPA adicionales.
 *
 * <p>Esta clase comprueba integridad referencial
 * y conservación de procedencia; no determina
 * si la evidencia es suficiente o pertinente.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir004_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-004 - Reutilización de evidencias entre ciclos"
)
class EvidenceCrossCyclePersistenceTest {

    /** Acceso JDBC a la base de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Identificadores necesarios para preparar
     * evaluaciones válidas dentro de un sistema.
     *
     * @param systemId identificador del sistema
     * @param objectiveId objetivo asociado
     */
    private record Context(
            UUID systemId,
            UUID objectiveId
    ) {
    }

    /**
     * Crea un sistema y un objetivo.
     *
     * @return contexto compartido de las pruebas
     */
    private Context createContext() {
        UUID systemId = UUID.randomUUID();
        UUID objectiveId = UUID.randomUUID();

        assertEquals(1, jdbcTemplate.update(
                """
                INSERT INTO system_objective (
                    system_id, name
                )
                VALUES (?, ?)
                """,
                systemId,
                "Sistema de validación IR-004"
        ));

        assertEquals(1, jdbcTemplate.update(
                """
                INSERT INTO objective (
                    objective_id, system_id, name
                )
                VALUES (?, ?, ?)
                """,
                objectiveId,
                systemId,
                "Objetivo de evaluación"
        ));

        return new Context(systemId, objectiveId);
    }

    /**
     * Crea un ciclo y asocia el objetivo indicado.
     *
     * <p>La asociación satisface IR-001 e IR-002,
     * permitiendo aislar el comportamiento IR-004.
     *
     * @param context contexto de sistema y objetivo
     * @return identificador del ciclo
     */
    private UUID createCycle(Context context) {
        UUID cycleId = UUID.randomUUID();

        assertEquals(1, jdbcTemplate.update(
                """
                INSERT INTO cycle (
                    cycle_id, system_id
                )
                VALUES (?, ?)
                """,
                cycleId,
                context.systemId()
        ));

        assertEquals(1, jdbcTemplate.update(
                """
                INSERT INTO cycle_objective (
                    cycle_id, objective_id, system_id
                )
                VALUES (?, ?, ?)
                """,
                cycleId,
                context.objectiveId(),
                context.systemId()
        ));

        return cycleId;
    }

    /**
     * Registra una evidencia con información
     * explícita de procedencia e integridad.
     *
     * @param originCycleId ciclo de origen
     * @return identificador de la evidencia
     */
    private UUID createEvidence(UUID originCycleId) {
        UUID evidenceId = UUID.randomUUID();

        assertEquals(1, jdbcTemplate.update(
                """
                INSERT INTO evidence (
                    evidence_id,
                    cycle_id,
                    source_type,
                    source_reference,
                    captured_at,
                    provenance,
                    integrity_reference,
                    quality_status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                evidenceId,
                originCycleId,
                "TEST_RESULT",
                "test://miec/ir004/result",
                Timestamp.from(Instant.now()),
                "Capturada originalmente en el ciclo de origen",
                "sha256:ir004-test-reference",
                "VERIFIED"
        ));

        return evidenceId;
    }

    /**
     * Crea una evaluación válida dentro de un ciclo.
     *
     * @param cycleId ciclo evaluado
     * @param objectiveId objetivo asociado
     * @return identificador de la evaluación
     */
    private UUID createEvaluation(
            UUID cycleId,
            UUID objectiveId
    ) {
        UUID evaluationId = UUID.randomUUID();

        assertEquals(1, jdbcTemplate.update(
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
        ));

        return evaluationId;
    }

    /**
     * Relaciona una evaluación con una evidencia.
     *
     * @param evaluationId evaluación existente
     * @param evidenceId evidencia existente
     * @return número de relaciones insertadas
     */
    private int linkEvidence(
            UUID evaluationId,
            UUID evidenceId
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO evaluation_evidence (
                    evaluation_id, evidence_id
                )
                VALUES (?, ?)
                """,
                evaluationId,
                evidenceId
        );
    }

    /**
     * Cuenta las relaciones de una evidencia.
     *
     * @param evidenceId identificador consultado
     * @return número de usos registrados
     */
    private int countUses(UUID evidenceId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_evidence
                WHERE evidence_id = ?
                """,
                Integer.class,
                evidenceId
        );

        return Objects.requireNonNull(count);
    }

    /**
     * Comprueba la asociación convencional
     * entre evidencia y evaluación del mismo ciclo.
     */
    @Test
    @DisplayName(
            "Debe asociar una evidencia a una evaluación del mismo ciclo"
    )
    void shouldLinkEvidenceWithinSameCycle() {
        Context context = createContext();
        UUID cycleId = createCycle(context);

        UUID evidenceId = createEvidence(cycleId);
        UUID evaluationId = createEvaluation(
                cycleId,
                context.objectiveId()
        );

        assertEquals(
                1,
                linkEvidence(evaluationId, evidenceId)
        );

        assertEquals(1, countUses(evidenceId));
    }

    /**
     * Comprueba que el ciclo de captura de
     * una evidencia pueda diferir del ciclo
     * donde posteriormente se utiliza.
     */
    @Test
    @DisplayName(
            "Debe permitir utilizar una evidencia de otro ciclo"
    )
    void shouldAllowEvidenceFromAnotherCycle() {
        Context context = createContext();

        UUID originCycle = createCycle(context);
        UUID evaluationCycle = createCycle(context);

        UUID evidenceId = createEvidence(originCycle);
        UUID evaluationId = createEvaluation(
                evaluationCycle,
                context.objectiveId()
        );

        assertNotEquals(originCycle, evaluationCycle);

        assertEquals(
                1,
                linkEvidence(evaluationId, evidenceId)
        );

        assertEquals(1, countUses(evidenceId));
    }

    /**
     * Verifica que la misma evidencia pueda
     * participar en múltiples evaluaciones,
     * incluso de ciclos diferentes.
     *
     * <p>No es necesario duplicar el registro
     * original de evidencia.
     */
    @Test
    @DisplayName(
            "Debe reutilizar la misma evidencia en varias evaluaciones"
    )
    void shouldReuseEvidenceAcrossEvaluations() {
        Context context = createContext();

        UUID firstCycle = createCycle(context);
        UUID secondCycle = createCycle(context);

        UUID evidenceId = createEvidence(firstCycle);

        UUID firstEvaluation = createEvaluation(
                firstCycle,
                context.objectiveId()
        );

        UUID secondEvaluation = createEvaluation(
                secondCycle,
                context.objectiveId()
        );

        assertEquals(
                1,
                linkEvidence(firstEvaluation, evidenceId)
        );

        assertEquals(
                1,
                linkEvidence(secondEvaluation, evidenceId)
        );

        assertEquals(2, countUses(evidenceId));

        Integer evidenceRows = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM evidence
                WHERE evidence_id = ?
                """,
                Integer.class,
                evidenceId
        );

        assertEquals(1, evidenceRows);
    }

    /**
     * Comprueba que reutilizar una evidencia
     * no cambie su ciclo de origen ni sus
     * atributos de procedencia.
     *
     * <p>La relación entre evaluaciones y evidencias
     * es independiente del registro de captura.
     */
    @Test
    @DisplayName(
            "Debe conservar el ciclo original y la procedencia"
    )
    void shouldPreserveEvidenceProvenance() {
        Context context = createContext();

        UUID originCycle = createCycle(context);
        UUID secondCycle = createCycle(context);

        UUID evidenceId = createEvidence(originCycle);

        UUID evaluationId = createEvaluation(
                secondCycle,
                context.objectiveId()
        );

        linkEvidence(evaluationId, evidenceId);

        var persistedEvidence = jdbcTemplate.queryForMap(
                """
                SELECT cycle_id, provenance, integrity_reference
                FROM evidence
                WHERE evidence_id = ?
                """,
                evidenceId
        );

        assertAll(
                () -> assertEquals(
                        originCycle,
                        persistedEvidence.get("CYCLE_ID")
                ),
                () -> assertEquals(
                        "Capturada originalmente en el ciclo de origen",
                        persistedEvidence.get("PROVENANCE")
                ),
                () -> assertEquals(
                        "sha256:ir004-test-reference",
                        persistedEvidence.get("INTEGRITY_REFERENCE")
                )
        );
    }

    /**
     * Verifica que una asociación no pueda
     * referenciar una evidencia inexistente.
     *
     * <p>La clave foránea protege la existencia
     * de la evidencia independientemente de
     * la regla de reutilización.
     */
    @Test
    @DisplayName(
            "Debe rechazar referencias a evidencias inexistentes"
    )
    void shouldRejectUnknownEvidence() {
        Context context = createContext();

        UUID cycleId = createCycle(context);
        UUID evaluationId = createEvaluation(
                cycleId,
                context.objectiveId()
        );

        UUID unknownEvidenceId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> linkEvidence(
                        evaluationId,
                        unknownEvidenceId
                )
        );

        assertEquals(0, countUses(unknownEvidenceId));
    }

    /**
     * Comprueba que la misma evidencia no
     * pueda asociarse dos veces a una evaluación.
     *
     * <p>La clave primaria compuesta de
     * evaluation_evidence evita duplicaciones
     * que podrían distorsionar la corroboración.
     */
    @Test
    @DisplayName(
            "Debe rechazar la asociación duplicada de evidencia y evaluación"
    )
    void shouldRejectDuplicateEvidenceAssociation() {
        Context context = createContext();

        UUID cycleId = createCycle(context);
        UUID evidenceId = createEvidence(cycleId);
        UUID evaluationId = createEvaluation(
                cycleId,
                context.objectiveId()
        );

        assertEquals(
                1,
                linkEvidence(evaluationId, evidenceId)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> linkEvidence(
                        evaluationId,
                        evidenceId
                )
        );

        assertEquals(1, countUses(evidenceId));
    }
}
