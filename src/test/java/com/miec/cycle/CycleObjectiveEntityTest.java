
package com.miec.cycle;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de CycleObjectiveId y CycleObjectiveEntity.
 *
 * <p>Comprueban las invariantes básicas de la asociación
 * ciclo-objetivo y la configuración JPA de la clave primaria.
 *
 * <p>Las restricciones compuestas de pertenencia al mismo
 * sistema se comprobarán posteriormente mediante pruebas
 * de integración con H2 y Flyway V002.
 */
@DisplayName("MIEC-F03 - Asociación ciclo-objetivo")
class CycleObjectiveEntityTest {

    /**
     * Verifica la construcción de una identidad compuesta.
     */
    @Test
    @DisplayName(
            "Debe construir una clave compuesta con ciclo y objetivo"
    )
    void shouldCreateCompositeId() {
        UUID cycleId = UUID.randomUUID();
        UUID objectiveId = UUID.randomUUID();

        CycleObjectiveId id =
                new CycleObjectiveId(cycleId, objectiveId);

        assertEquals(cycleId, id.getCycleId());
        assertEquals(objectiveId, id.getObjectiveId());
    }

    /**
     * Verifica la obligatoriedad del identificador del ciclo.
     */
    @Test
    @DisplayName(
            "Debe rechazar una clave compuesta sin ciclo"
    )
    void shouldRejectNullCycleInId() {
        assertThrows(
                NullPointerException.class,
                () -> new CycleObjectiveId(
                        null,
                        UUID.randomUUID()
                )
        );
    }

    /**
     * Verifica la obligatoriedad del objetivo.
     */
    @Test
    @DisplayName(
            "Debe rechazar una clave compuesta sin objetivo"
    )
    void shouldRejectNullObjectiveInId() {
        assertThrows(
                NullPointerException.class,
                () -> new CycleObjectiveId(
                        UUID.randomUUID(),
                        null
                )
        );
    }

    /**
     * Comprueba la igualdad de claves equivalentes.
     */
    @Test
    @DisplayName(
            "Debe considerar iguales las claves del mismo ciclo y objetivo"
    )
    void shouldCompareEqualIds() {
        UUID cycleId = UUID.randomUUID();
        UUID objectiveId = UUID.randomUUID();

        CycleObjectiveId first =
                new CycleObjectiveId(cycleId, objectiveId);

        CycleObjectiveId second =
                new CycleObjectiveId(cycleId, objectiveId);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * Comprueba que dos objetivos distintos generan
     * claves distintas aun cuando compartan ciclo.
     */
    @Test
    @DisplayName(
            "Debe distinguir objetivos diferentes dentro del mismo ciclo"
    )
    void shouldDistinguishDifferentObjectives() {
        UUID cycleId = UUID.randomUUID();

        CycleObjectiveId first =
                new CycleObjectiveId(
                        cycleId,
                        UUID.randomUUID()
                );

        CycleObjectiveId second =
                new CycleObjectiveId(
                        cycleId,
                        UUID.randomUUID()
                );

        assertNotEquals(first, second);
    }

    /**
     * Comprueba que dos ciclos diferentes generan
     * claves distintas para un mismo objetivo.
     */
    @Test
    @DisplayName(
            "Debe distinguir ciclos diferentes asociados al mismo objetivo"
    )
    void shouldDistinguishDifferentCycles() {
        UUID objectiveId = UUID.randomUUID();

        CycleObjectiveId first =
                new CycleObjectiveId(
                        UUID.randomUUID(),
                        objectiveId
                );

        CycleObjectiveId second =
                new CycleObjectiveId(
                        UUID.randomUUID(),
                        objectiveId
                );

        assertNotEquals(first, second);
    }

    /**
     * Comprueba la utilización de claves compuestas
     * en colecciones basadas en hash.
     */
    @Test
    @DisplayName(
            "Debe evitar duplicados de claves equivalentes en un conjunto"
    )
    void shouldPreventDuplicateIdsInSet() {
        UUID cycleId = UUID.randomUUID();
        UUID objectiveId = UUID.randomUUID();

        Set<CycleObjectiveId> ids = new HashSet<>();

        ids.add(new CycleObjectiveId(cycleId, objectiveId));
        ids.add(new CycleObjectiveId(cycleId, objectiveId));

        assertEquals(1, ids.size());
    }

    /**
     * Comprueba el contrato de serialización de JPA.
     */
    @Test
    @DisplayName(
            "Debe implementar Serializable en la clave compuesta"
    )
    void shouldImplementSerializable() {
        CycleObjectiveId id = new CycleObjectiveId(
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        assertInstanceOf(Serializable.class, id);
    }

    /**
     * Comprueba la construcción de una asociación válida.
     */
    @Test
    @DisplayName(
            "Debe crear una asociación con ciclo, objetivo y sistema"
    )
    void shouldCreateCycleObjectiveEntity() {
        UUID cycleId = UUID.randomUUID();
        UUID objectiveId = UUID.randomUUID();
        UUID systemId = UUID.randomUUID();

        CycleObjectiveEntity entity =
                new CycleObjectiveEntity(
                        cycleId,
                        objectiveId,
                        systemId
                );

        assertEquals(cycleId, entity.getCycleId());
        assertEquals(objectiveId, entity.getObjectiveId());
        assertEquals(systemId, entity.getSystemId());
        assertNotNull(entity.getId());
    }

    /**
     * Comprueba que el sistema sea obligatorio.
     */
    @Test
    @DisplayName(
            "Debe rechazar una asociación sin sistema objetivo"
    )
    void shouldRejectNullSystem() {
        assertThrows(
                NullPointerException.class,
                () -> new CycleObjectiveEntity(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null
                )
        );
    }

    /**
     * Comprueba la coincidencia de la asociación.
     */
    @Test
    @DisplayName(
            "Debe reconocer la pareja ciclo-objetivo asociada"
    )
    void shouldMatchAssociatedIds() {
        UUID cycleId = UUID.randomUUID();
        UUID objectiveId = UUID.randomUUID();

        CycleObjectiveEntity entity =
                new CycleObjectiveEntity(
                        cycleId,
                        objectiveId,
                        UUID.randomUUID()
                );

        assertTrue(entity.matches(cycleId, objectiveId));
        assertFalse(
                entity.matches(UUID.randomUUID(), objectiveId)
        );
    }

    /**
     * Verifica la anotación JPA de la clave compuesta.
     */
    @Test
    @DisplayName(
            "Debe declarar CycleObjectiveId como clave embebida JPA"
    )
    void shouldDeclareEmbeddableId() {
        assertTrue(
                CycleObjectiveId.class.isAnnotationPresent(
                        Embeddable.class
                )
        );
    }

    /**
     * Verifica el mapeo principal de la entidad.
     */
    @Test
    @DisplayName(
            "Debe mapear la entidad a la tabla cycle_objective"
    )
    void shouldMapCycleObjectiveTable()
            throws NoSuchFieldException {

        assertTrue(
                CycleObjectiveEntity.class.isAnnotationPresent(
                        Entity.class
                )
        );

        Table table =
                CycleObjectiveEntity.class.getAnnotation(Table.class);

        assertNotNull(table);
        assertEquals("cycle_objective", table.name());

        Field idField =
                CycleObjectiveEntity.class.getDeclaredField("id");

        assertTrue(
                idField.isAnnotationPresent(EmbeddedId.class)
        );
    }

    /**
     * Verifica que la columna system_id sea obligatoria.
     */
    @Test
    @DisplayName(
            "Debe mapear system_id como columna obligatoria"
    )
    void shouldMapRequiredSystemId()
            throws NoSuchFieldException {

        Field systemField =
                CycleObjectiveEntity.class.getDeclaredField(
                        "systemId"
                );

        Column column = systemField.getAnnotation(Column.class);

        assertNotNull(column);
        assertEquals("system_id", column.name());
        assertFalse(column.nullable());
    }
}
