
package com.miec.objective;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de ObjectiveEntity.
 *
 * <p>Verifican las invariantes fundamentales de
 * MIEC-F03 ENT-03: identificación, sistema propietario,
 * descripción, criterios de éxito, prioridad,
 * estado, vigencia y metadatos.
 *
 * <p>Las pruebas de integración de las restricciones
 * de Flyway se ejecutarán posteriormente contra H2.
 */
@DisplayName("MIEC-F03 ENT-03 - Objetivo")
class ObjectiveEntityTest {

    /**
     * Verifica la creación de un objetivo válido.
     */
    @Test
    @DisplayName(
            "Debe crear un objetivo asociado a un sistema"
    )
    void shouldCreateObjectiveWithSystem() {
        UUID systemId = UUID.randomUUID();

        ObjectiveEntity entity =
                new ObjectiveEntity(systemId, "Mejorar calidad");

        assertEquals(systemId, entity.getSystemId());
        assertEquals("Mejorar calidad", entity.getName());
        assertNull(entity.getObjectiveId());
    }

    /**
     * Comprueba el rechazo de sistemas nulos.
     */
    @Test
    @DisplayName(
            "Debe rechazar objetivos sin sistema propietario"
    )
    void shouldRejectNullSystem() {
        assertThrows(
                NullPointerException.class,
                () -> new ObjectiveEntity(
                        null,
                        "Objetivo de prueba"
                )
        );
    }

    /**
     * Comprueba que el nombre es obligatorio.
     */
    @Test
    @DisplayName(
            "Debe rechazar nombres de objetivo nulos o vacíos"
    )
    void shouldRejectInvalidName() {
        UUID systemId = UUID.randomUUID();

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ObjectiveEntity(systemId, null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ObjectiveEntity(systemId, "   ")
                )
        );
    }

    /**
     * Comprueba la longitud máxima del nombre.
     */
    @Test
    @DisplayName(
            "Debe rechazar nombres superiores a 255 caracteres"
    )
    void shouldRejectOversizedName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ObjectiveEntity(
                        UUID.randomUUID(),
                        "N".repeat(256)
                )
        );
    }

    /**
     * Comprueba los valores por defecto.
     */
    @Test
    @DisplayName(
            "Debe inicializar el objetivo activo y con prioridad cero"
    )
    void shouldInitializeDefaults() {
        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Objetivo MIEC"
                );

        assertEquals("ACTIVE", entity.getStatus());
        assertEquals(0, entity.getPriority());
    }

    /**
     * Comprueba la normalización de atributos de texto.
     */
    @Test
    @DisplayName(
            "Debe normalizar el nombre del objetivo"
    )
    void shouldTrimObjectiveName() {
        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "  Mejorar desempeño  "
                );

        assertEquals(
                "Mejorar desempeño",
                entity.getName()
        );
    }

    /**
     * Comprueba la descripción y los criterios de éxito.
     */
    @Test
    @DisplayName(
            "Debe permitir definir descripción y criterios de éxito"
    )
    void shouldSetDescriptionAndSuccessCriteria() {
        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Mejorar pruebas"
                )
                        .description("Incrementar cobertura")
                        .successCriteria("Cobertura mayor al 80%");

        assertEquals(
                "Incrementar cobertura",
                entity.getDescription()
        );
        assertEquals(
                "Cobertura mayor al 80%",
                entity.getSuccessCriteria()
        );
    }

    /**
     * Comprueba la modificación de prioridad.
     */
    @Test
    @DisplayName(
            "Debe permitir modificar la prioridad del objetivo"
    )
    void shouldUpdatePriority() {
        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Objetivo prioritario"
                ).priority(5);

        assertEquals(5, entity.getPriority());
    }

    /**
     * Comprueba la validación de estados vacíos.
     */
    @Test
    @DisplayName(
            "Debe rechazar estados nulos o vacíos"
    )
    void shouldRejectInvalidStatus() {
        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Objetivo MIEC"
                );

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> entity.status(null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> entity.status(" ")
                )
        );
    }

    /**
     * Comprueba períodos de vigencia correctos.
     */
    @Test
    @DisplayName(
            "Debe permitir establecer un período de vigencia válido"
    )
    void shouldAcceptValidPeriod() {
        Instant from = Instant.parse(
                "2026-01-01T00:00:00Z"
        );
        Instant until = Instant.parse(
                "2026-12-31T23:59:59Z"
        );

        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Objetivo anual"
                )
                        .validFrom(from)
                        .validUntil(until);

        assertEquals(from, entity.getValidFrom());
        assertEquals(until, entity.getValidUntil());
    }

    /**
     * Comprueba el rechazo de períodos inconsistentes.
     */
    @Test
    @DisplayName(
            "Debe rechazar una vigencia final anterior al inicio"
    )
    void shouldRejectInvalidPeriod() {
        Instant from = Instant.parse(
                "2026-12-31T00:00:00Z"
        );
        Instant until = Instant.parse(
                "2026-01-01T00:00:00Z"
        );

        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Objetivo temporal"
                ).validFrom(from);

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.validUntil(until)
        );

        assertNull(entity.getValidUntil());
    }

    /**
     * Comprueba las referencias de trazabilidad.
     */
    @Test
    @DisplayName(
            "Debe permitir registrar versión y creador"
    )
    void shouldSetVersionAndCreator() {
        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Objetivo MIEC"
                )
                        .versionId("V001")
                        .createdBy("USER-001");

        assertEquals("V001", entity.getVersionId());
        assertEquals("USER-001", entity.getCreatedBy());
    }

    /**
     * Comprueba los límites de las referencias.
     */
    @Test
    @DisplayName(
            "Debe rechazar referencias de versión demasiado largas"
    )
    void shouldRejectOversizedVersion() {
        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Objetivo MIEC"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.versionId("V".repeat(101))
        );
    }

    /**
     * Comprueba la inicialización de la fecha de creación.
     */
    @Test
    @DisplayName(
            "Debe inicializar la fecha de creación antes de persistir"
    )
    void shouldInitializeCreatedAt() {
        ObjectiveEntity entity =
                new ObjectiveEntity(
                        UUID.randomUUID(),
                        "Objetivo MIEC"
                );

        Instant before = Instant.now();

        entity.onCreate();

        Instant after = Instant.now();

        assertNotNull(entity.getCreatedAt());
        assertFalse(
                entity.getCreatedAt().isBefore(before)
        );
        assertFalse(
                entity.getCreatedAt().isAfter(after)
        );
    }

    /**
     * Comprueba las anotaciones JPA fundamentales.
     */
    @Test
    @DisplayName(
            "Debe mapear la entidad a la tabla objective"
    )
    void shouldHaveCorrectJpaMapping()
            throws NoSuchFieldException {

        assertTrue(
                ObjectiveEntity.class.isAnnotationPresent(
                        Entity.class
                )
        );

        Table table =
                ObjectiveEntity.class.getAnnotation(Table.class);

        assertNotNull(table);
        assertEquals("objective", table.name());

        Field idField =
                ObjectiveEntity.class.getDeclaredField(
                        "objectiveId"
                );

        assertTrue(idField.isAnnotationPresent(Id.class));

        Column idColumn = idField.getAnnotation(Column.class);

        assertNotNull(idColumn);
        assertEquals("objective_id", idColumn.name());
    }
}
