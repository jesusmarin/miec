
package com.miec.cycle;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de CycleEntity.
 *
 * <p>Verifican las invariantes básicas de MIEC-F03 ENT-02,
 * los atributos del ciclo, sus fechas de auditoría
 * y las anotaciones fundamentales de persistencia.
 *
 * <p>Estas pruebas no sustituyen las pruebas de
 * integración JPA y Flyway contra H2.
 */
@DisplayName("MIEC-F03 ENT-02 - Ciclo de evolución")
class CycleEntityTest {

    /**
     * Comprueba la construcción inicial del ciclo.
     */
    @Test
    @DisplayName(
            "Debe crear un ciclo asociado a un sistema válido"
    )
    void shouldCreateCycleWithSystem() {
        UUID systemId = UUID.randomUUID();

        CycleEntity entity = new CycleEntity(systemId);

        assertEquals(systemId, entity.getSystemId());
        assertNull(entity.getCycleId());
        assertNull(entity.getParentCycleId());
    }

    /**
     * Comprueba que el sistema es obligatorio.
     */
    @Test
    @DisplayName(
            "Debe rechazar la creación de un ciclo sin sistema"
    )
    void shouldRejectNullSystem() {
        assertThrows(
                NullPointerException.class,
                () -> new CycleEntity(null)
        );
    }

    /**
     * Comprueba los valores iniciales de operación.
     */
    @Test
    @DisplayName(
            "Debe inicializar el estado E00_INACTIVO y autonomía A0"
    )
    void shouldInitializeDefaultState() {
        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        assertEquals("E00_INACTIVO", entity.getState());
        assertEquals("A0", entity.getAutonomyLevel());
    }

    /**
     * Comprueba el número ordinal opcional.
     */
    @Test
    @DisplayName(
            "Debe permitir establecer el número del ciclo"
    )
    void shouldSetCycleNumber() {
        CycleEntity entity =
                new CycleEntity(UUID.randomUUID())
                        .cycleNumber(3);

        assertEquals(3, entity.getCycleNumber());
    }

    /**
     * Comprueba la referencia a un ciclo padre.
     */
    @Test
    @DisplayName(
            "Debe permitir asociar un ciclo padre"
    )
    void shouldSetParentCycle() {
        UUID parentId = UUID.randomUUID();

        CycleEntity entity =
                new CycleEntity(UUID.randomUUID())
                        .parentCycleId(parentId);

        assertEquals(parentId, entity.getParentCycleId());
    }

    /**
     * Comprueba la validación de autorreferencias directas.
     */
    @Test
    @DisplayName(
            "Debe rechazar que un ciclo persistido sea su propio padre"
    )
    void shouldRejectSelfParent()
            throws ReflectiveOperationException {

        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        UUID cycleId = UUID.randomUUID();

        Field idField =
                CycleEntity.class.getDeclaredField("cycleId");

        idField.setAccessible(true);
        idField.set(entity, cycleId);

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.parentCycleId(cycleId)
        );
    }

    /**
     * Comprueba la obligatoriedad del estado.
     */
    @Test
    @DisplayName(
            "Debe rechazar estados nulos o vacíos"
    )
    void shouldRejectInvalidState() {
        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> entity.state(null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> entity.state(" ")
                )
        );
    }

    /**
     * Comprueba la obligatoriedad de la autonomía.
     */
    @Test
    @DisplayName(
            "Debe rechazar niveles de autonomía vacíos"
    )
    void shouldRejectBlankAutonomyLevel() {
        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.autonomyLevel(" ")
        );
    }

    /**
     * Comprueba el límite de longitud de configuración.
     */
    @Test
    @DisplayName(
            "Debe rechazar referencias de configuración demasiado largas"
    )
    void shouldRejectOversizedConfigurationReference() {
        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.configurationVersionId(
                        "V".repeat(101)
                )
        );
    }

    /**
     * Comprueba la longitud máxima de la justificación.
     */
    @Test
    @DisplayName(
            "Debe rechazar motivos de cierre superiores a 255 caracteres"
    )
    void shouldRejectOversizedClosureReason() {
        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.closureReason("X".repeat(256))
        );
    }

    /**
     * Comprueba la inicialización de auditoría.
     */
    @Test
    @DisplayName(
            "Debe inicializar las fechas de auditoría al insertar"
    )
    void shouldInitializeAuditDates() {
        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        Instant before = Instant.now();

        entity.onCreate();

        Instant after = Instant.now();

        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertFalse(entity.getCreatedAt().isBefore(before));
        assertFalse(entity.getCreatedAt().isAfter(after));
        assertEquals(
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * Comprueba la conservación de la fecha de creación.
     */
    @Test
    @DisplayName(
            "Debe conservar la fecha de creación al actualizar"
    )
    void shouldPreserveCreatedAtOnUpdate() {
        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        entity.onCreate();

        Instant createdAt = entity.getCreatedAt();

        entity.state("E01_INICIALIZANDO");
        entity.onUpdate();

        assertEquals(createdAt, entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertFalse(
                entity.getUpdatedAt().isBefore(createdAt)
        );
    }

    /**
     * Comprueba las anotaciones principales de la entidad.
     */
    @Test
    @DisplayName(
            "Debe mapear correctamente la tabla cycle y su clave primaria"
    )
    void shouldHaveJpaMappings()
            throws NoSuchFieldException {

        assertTrue(
                CycleEntity.class.isAnnotationPresent(Entity.class)
        );

        Table table =
                CycleEntity.class.getAnnotation(Table.class);

        assertNotNull(table);
        assertEquals("cycle", table.name());

        Field idField =
                CycleEntity.class.getDeclaredField("cycleId");

        assertTrue(idField.isAnnotationPresent(Id.class));
    }

    /**
     * Comprueba la configuración del bloqueo optimista.
     */
    @Test
    @DisplayName(
            "Debe utilizar @Version para controlar actualizaciones concurrentes"
    )
    void shouldUseOptimisticLocking()
            throws NoSuchFieldException {

        Field versionField =
                CycleEntity.class.getDeclaredField("version");

        assertTrue(
                versionField.isAnnotationPresent(Version.class)
        );

        CycleEntity entity =
                new CycleEntity(UUID.randomUUID());

        assertEquals(0L, entity.getVersion());
    }
}
