
package com.miec.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de DecisionRecordEntity.
 *
 * <p>Verifican las invariantes de MIEC-F03 ENT-11,
 * las referencias de trazabilidad, las validaciones
 * básicas y el mapeo de persistencia JPA.
 *
 * <p>La integridad referencial IR-006 se comprobará
 * mediante pruebas de integración contra Flyway V002.
 */
@DisplayName("MIEC-F03 ENT-11 - Registro de decisión")
class DecisionRecordEntityTest {

    /**
     * Verifica la construcción básica.
     */
    @Test
    @DisplayName("Debe crear una decisión válida para un ciclo")
    void shouldCreateValidDecision() {
        UUID cycleId = UUID.randomUUID();

        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        cycleId, "CONTINUE", "PROPOSED"
                );

        assertEquals(cycleId, entity.getCycleId());
        assertEquals("CONTINUE", entity.getDecisionType());
        assertEquals("PROPOSED", entity.getDecisionStatus());
        assertNull(entity.getDecisionId());
        assertNotNull(entity.getCreatedAt());
    }

    /**
     * Verifica la obligatoriedad del ciclo.
     */
    @Test
    @DisplayName("Debe rechazar una decisión sin ciclo")
    void shouldRejectNullCycle() {
        assertThrows(
                NullPointerException.class,
                () -> new DecisionRecordEntity(
                        null, "CONTINUE", "PROPOSED"
                )
        );
    }

    /**
     * Verifica el tipo obligatorio.
     */
    @Test
    @DisplayName("Debe rechazar tipos de decisión nulos o vacíos")
    void shouldRejectInvalidDecisionType() {
        UUID id = UUID.randomUUID();

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new DecisionRecordEntity(
                                id, null, "PROPOSED"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionRecordEntity(
                                id, " ", "PROPOSED"
                        )
                )
        );
    }

    /**
     * Verifica el estado obligatorio.
     */
    @Test
    @DisplayName("Debe rechazar estados de decisión nulos o vacíos")
    void shouldRejectInvalidDecisionStatus() {
        UUID id = UUID.randomUUID();

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new DecisionRecordEntity(
                                id, "CONTINUE", null
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionRecordEntity(
                                id, "CONTINUE", " "
                        )
                )
        );
    }

    /**
     * Verifica la longitud máxima del tipo.
     */
    @Test
    @DisplayName("Debe rechazar tipos de decisión superiores a 50 caracteres")
    void shouldRejectOversizedDecisionType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionRecordEntity(
                        UUID.randomUUID(),
                        "X".repeat(51),
                        "PROPOSED"
                )
        );
    }

    /**
     * Verifica la longitud máxima del estado.
     */
    @Test
    @DisplayName("Debe rechazar estados superiores a 50 caracteres")
    void shouldRejectOversizedDecisionStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionRecordEntity(
                        UUID.randomUUID(),
                        "CONTINUE",
                        "X".repeat(51)
                )
        );
    }

    /**
     * Verifica la normalización de textos obligatorios.
     */
    @Test
    @DisplayName("Debe normalizar el tipo y estado de decisión")
    void shouldNormalizeRequiredTexts() {
        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(),
                        " CONTINUE ",
                        " PROPOSED "
                );

        assertEquals("CONTINUE", entity.getDecisionType());
        assertEquals("PROPOSED", entity.getDecisionStatus());
    }

    /**
     * Verifica que la evaluación sea opcional.
     */
    @Test
    @DisplayName("Debe permitir decisiones sin evaluación asociada")
    void shouldAllowNullEvaluation() {
        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(), "SUSPEND", "PROPOSED"
                );

        assertNull(entity.getEvaluationId());
        assertSame(entity, entity.evaluationId(null));
    }

    /**
     * Verifica la asignación de evaluación.
     */
    @Test
    @DisplayName("Debe conservar la referencia a una evaluación")
    void shouldSetEvaluationId() {
        UUID evaluationId = UUID.randomUUID();

        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(), "ADJUST", "PROPOSED"
                ).evaluationId(evaluationId);

        assertEquals(evaluationId, entity.getEvaluationId());
    }

    /**
     * Verifica los metadatos principales de decisión.
     */
    @Test
    @DisplayName("Debe conservar la recomendación y su justificación")
    void shouldSetRecommendationAndRationale() {
        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(), "CONTINUE", "PROPOSED"
                )
                        .recommendation("CONTINUE")
                        .selectionRationale("Evidencia suficiente");

        assertEquals("CONTINUE", entity.getRecommendation());
        assertEquals(
                "Evidencia suficiente",
                entity.getSelectionRationale()
        );
    }

    /**
     * Verifica las columnas de contenido JSON.
     */
    @Test
    @DisplayName("Debe conservar alternativas, riesgos y transición propuesta")
    void shouldSetJsonFields() {
        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(), "ADJUST", "PROPOSED"
                )
                        .alternativesConsidered("[\"A\", \"B\"]")
                        .riskConstraints("{\"risk\":\"LOW\"}")
                        .proposedTransition("{\"to\":\"E10\"}");

        assertEquals(
                "[\"A\", \"B\"]",
                entity.getAlternativesConsidered()
        );
        assertEquals(
                "{\"risk\":\"LOW\"}",
                entity.getRiskConstraints()
        );
        assertEquals(
                "{\"to\":\"E10\"}",
                entity.getProposedTransition()
        );
    }

    /**
     * Verifica las referencias de auditoría y autorización.
     */
    @Test
    @DisplayName("Debe registrar autorización, transición y versión de reglas")
    void shouldSetTraceabilityReferences() {
        UUID transitionId = UUID.randomUUID();

        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(), "CONTINUE", "PROPOSED"
                )
                        .authorizationStatus("PENDING")
                        .actualTransitionId(transitionId)
                        .decisionRuleVersionId("MIEC-F02-v1");

        assertEquals(
                "PENDING",
                entity.getAuthorizationStatus()
        );
        assertEquals(
                transitionId,
                entity.getActualTransitionId()
        );
        assertEquals(
                "MIEC-F02-v1",
                entity.getDecisionRuleVersionId()
        );
    }

    /**
     * Verifica los límites de los campos opcionales.
     */
    @Test
    @DisplayName("Debe rechazar recomendaciones superiores a 100 caracteres")
    void shouldRejectOversizedRecommendation() {
        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(), "CONTINUE", "PROPOSED"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.recommendation("R".repeat(101))
        );
    }

    /**
     * Verifica la longitud de la versión de reglas.
     */
    @Test
    @DisplayName("Debe rechazar referencias de reglas superiores a 100 caracteres")
    void shouldRejectOversizedRuleVersion() {
        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(), "CONTINUE", "PROPOSED"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.decisionRuleVersionId("V".repeat(101))
        );
    }

    /**
     * Verifica el comportamiento del callback JPA.
     */
    @Test
    @DisplayName("Debe mantener la fecha de creación al preparar la inserción")
    void shouldPreserveCreatedAtOnPersist() {
        DecisionRecordEntity entity =
                new DecisionRecordEntity(
                        UUID.randomUUID(), "CONTINUE", "PROPOSED"
                );

        Instant createdAt = entity.getCreatedAt();

        entity.onCreate();

        assertEquals(createdAt, entity.getCreatedAt());
    }

    /**
     * Verifica el nombre físico de la tabla.
     */
    @Test
    @DisplayName("Debe mapear la entidad a la tabla decision_record")
    void shouldMapDecisionRecordTable()
            throws NoSuchFieldException {

        assertTrue(
                DecisionRecordEntity.class.isAnnotationPresent(
                        Entity.class
                )
        );

        Table table =
                DecisionRecordEntity.class.getAnnotation(Table.class);

        assertNotNull(table);
        assertEquals("decision_record", table.name());

        Field id =
                DecisionRecordEntity.class.getDeclaredField(
                        "decisionId"
                );

        assertTrue(id.isAnnotationPresent(Id.class));
    }

    /**
     * Verifica las columnas involucradas en IR-006.
     */
    @Test
    @DisplayName("Debe mapear los identificadores de ciclo y evaluación")
    void shouldMapCycleAndEvaluationColumns()
            throws NoSuchFieldException {

        Field cycle =
                DecisionRecordEntity.class.getDeclaredField(
                        "cycleId"
                );

        Field evaluation =
                DecisionRecordEntity.class.getDeclaredField(
                        "evaluationId"
                );

        Column cycleColumn =
                cycle.getAnnotation(Column.class);

        Column evaluationColumn =
                evaluation.getAnnotation(Column.class);

        assertNotNull(cycleColumn);
        assertNotNull(evaluationColumn);
        assertEquals("cycle_id", cycleColumn.name());
        assertEquals("evaluation_id", evaluationColumn.name());
        assertFalse(cycleColumn.nullable());
    }

    /**
     * Verifica la declaración del callback de creación.
     */
    @Test
    @DisplayName("Debe declarar el callback JPA PrePersist")
    void shouldDeclarePrePersistCallback()
            throws NoSuchMethodException {

        assertTrue(
                DecisionRecordEntity.class
                        .getDeclaredMethod("onCreate")
                        .isAnnotationPresent(PrePersist.class)
        );
    }
}
