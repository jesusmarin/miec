
package com.miec.system;

import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de SystemObjectiveEntity.
 *
 * <p>Verifican las invariantes básicas de ENT-01,
 * la inicialización del estado, la gestión de fechas
 * y el manejo de atributos opcionales.
 *
 * <p>No sustituyen las pruebas de integración JPA,
 * que deben verificar la persistencia real en H2.
 */
@DisplayName("MIEC-F03 ENT-01 - Sistema objetivo")
class SystemObjectiveEntityTest {

    /**
     * Verifica el estado inicial del sistema.
     */
    @Test
    @DisplayName(
            "Debe crear un sistema objetivo activo con nombre válido"
    )
    void shouldCreateActiveSystemObjective() {
        SystemObjectiveEntity entity =
                new SystemObjectiveEntity("MIEC");

        assertEquals("MIEC", entity.getName());
        assertEquals("ACTIVE", entity.getStatus());
        assertNull(entity.getSystemId());
    }

    /**
     * Verifica la normalización del nombre.
     */
    @Test
    @DisplayName(
            "Debe eliminar espacios externos del nombre"
    )
    void shouldTrimName() {
        SystemObjectiveEntity entity =
                new SystemObjectiveEntity("  Sistema MIEC  ");

        assertEquals("Sistema MIEC", entity.getName());
    }

    /**
     * Verifica el rechazo de nombres nulos.
     */
    @Test
    @DisplayName(
            "Debe rechazar un nombre nulo"
    )
    void shouldRejectNullName() {
        assertThrows(
                NullPointerException.class,
                () -> new SystemObjectiveEntity(null)
        );
    }

    /**
     * Verifica el rechazo de nombres vacíos.
     */
    @Test
    @DisplayName(
            "Debe rechazar un nombre vacío"
    )
    void shouldRejectBlankName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemObjectiveEntity("   ")
        );
    }

    /**
     * Verifica el límite de longitud definido en SQL.
     */
    @Test
    @DisplayName(
            "Debe rechazar nombres superiores a 255 caracteres"
    )
    void shouldRejectOversizedName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemObjectiveEntity("A".repeat(256))
        );
    }

    /**
     * Verifica el almacenamiento de atributos opcionales.
     */
    @Test
    @DisplayName(
            "Debe permitir configurar dominio, descripción y responsable"
    )
    void shouldSetOptionalAttributes() {
        SystemObjectiveEntity entity =
                new SystemObjectiveEntity("MIEC")
                        .domain("SOFTWARE")
                        .description("Sistema de evolución continua")
                        .ownerReference("OWNER-001");

        assertEquals("SOFTWARE", entity.getDomain());
        assertEquals(
                "Sistema de evolución continua",
                entity.getDescription()
        );
        assertEquals("OWNER-001", entity.getOwnerReference());
    }

    /**
     * Verifica los límites de las columnas opcionales.
     */
    @Test
    @DisplayName(
            "Debe rechazar dominios superiores a 100 caracteres"
    )
    void shouldRejectOversizedDomain() {
        SystemObjectiveEntity entity =
                new SystemObjectiveEntity("MIEC");

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.domain("D".repeat(101))
        );
    }

    /**
     * Verifica el manejo defensivo de restricciones JSON.
     */
    @Test
    @DisplayName(
            "Debe proteger las restricciones frente a modificaciones externas"
    )
    void shouldDefensivelyCopyConstraints() {
        Map<String, Object> original = new HashMap<>();
        original.put("maxCycles", 10);

        SystemObjectiveEntity entity =
                new SystemObjectiveEntity("MIEC")
                        .constraints(original);

        original.put("maxCycles", 20);

        assertEquals(
                10,
                entity.getConstraints().get("maxCycles")
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> entity.getConstraints().put("newKey", true)
        );
    }

    /**
     * Verifica el rechazo de estados inválidos.
     */
    @Test
    @DisplayName(
            "Debe rechazar un estado vacío"
    )
    void shouldRejectBlankStatus() {
        SystemObjectiveEntity entity =
                new SystemObjectiveEntity("MIEC");

        assertThrows(
                IllegalArgumentException.class,
                () -> entity.status(" ")
        );
    }

    /**
     * Verifica la inicialización de fechas de auditoría.
     */
    @Test
    @DisplayName(
            "Debe inicializar las fechas al preparar la inserción"
    )
    void shouldInitializeAuditDates() {
        SystemObjectiveEntity entity =
                new SystemObjectiveEntity("MIEC");

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
     * Verifica la actualización de la fecha de modificación.
     */
    @Test
    @DisplayName(
            "Debe conservar la fecha de creación al actualizar"
    )
    void shouldPreserveCreatedAtOnUpdate() {
        SystemObjectiveEntity entity =
                new SystemObjectiveEntity("MIEC");

        entity.onCreate();

        Instant createdAt = entity.getCreatedAt();

        entity.description("Descripción actualizada");
        entity.onUpdate();

        assertEquals(createdAt, entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertFalse(
                entity.getUpdatedAt().isBefore(createdAt)
        );
    }

    /**
     * Verifica que la entidad esté correctamente anotada.
     */
    @Test
    @DisplayName(
            "Debe mapear la entidad a la tabla system_objective"
    )
    void shouldMapCorrectTable() {
        assertTrue(
                SystemObjectiveEntity.class.isAnnotationPresent(
                        Entity.class
                )
        );

        Table table =
                SystemObjectiveEntity.class.getAnnotation(Table.class);

        assertNotNull(table);
        assertEquals("system_objective", table.name());
    }

    /**
     * Verifica la presencia del callback JPA de creación.
     */
    @Test
    @DisplayName(
            "Debe registrar el callback JPA de creación"
    )
    void shouldDeclarePrePersistCallback()
            throws NoSuchMethodException {

        assertTrue(
                SystemObjectiveEntity.class
                        .getDeclaredMethod("onCreate")
                        .isAnnotationPresent(PrePersist.class)
        );
    }
}
