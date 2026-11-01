
package com.miec.cycle;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de la encapsulación
 * de escrituras jerárquicas de MIEC-F03 IR-013.
 *
 * <p>Verifican que:
 * <ul>
 *   <li>JPA no inserta parent_cycle_id.</li>
 *   <li>JPA no actualiza parent_cycle_id.</li>
 *   <li>CycleHierarchyService puede modificar
 *       la relación y JPA puede consultarla.</li>
 * </ul>
 *
 * <p>Los cambios directos por reflexión se usan
 * únicamente para probar que Hibernate ignora
 * el campo no insertable y no actualizable.
 *
 * <p>Esta clase no autoriza el uso de reflexión
 * para modificar relaciones en producción.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir013_readonly_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-013 - Protección de escrituras JPA"
)
class CycleParentReadOnlyPersistenceTest {

    /** Gestor de persistencia JPA. */
    @Autowired
    private EntityManager entityManager;

    /** Acceso JDBC para verificar el valor persistido. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Servicio autorizado para modificar la jerarquía. */
    @Autowired
    private CycleHierarchyService hierarchyService;

    /**
     * Crea un sistema objetivo de prueba.
     *
     * @return identificador del sistema
     */
    private UUID createSystem() {
        UUID systemId = UUID.randomUUID();

        assertEquals(
                1,
                jdbcTemplate.update(
                        """
                        INSERT INTO system_objective (
                            system_id, name
                        )
                        VALUES (?, ?)
                        """,
                        systemId,
                        "Sistema de protección IR-013"
                )
        );

        return systemId;
    }

    /**
     * Persiste un ciclo mediante JPA y fuerza
     * la ejecución de sus instrucciones SQL.
     *
     * @param systemId sistema propietario
     * @return entidad persistida
     */
    private CycleEntity createCycle(UUID systemId) {
        CycleEntity entity = new CycleEntity(systemId);

        entityManager.persist(entity);
        entityManager.flush();

        return entity;
    }

    /**
     * Consulta directamente el padre almacenado.
     *
     * @param cycleId identificador consultado
     * @return UUID del padre o null
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
     * Simula una modificación interna del campo
     * para comprobar las restricciones del mapeo JPA.
     *
     * <p>Se utiliza reflexión solo en el entorno
     * de pruebas porque no existe un setter público.
     *
     * @param entity entidad que será modificada
     * @param parentId valor que se intentará persistir
     */
    private void setParentForTest(
            CycleEntity entity,
            UUID parentId
    ) throws ReflectiveOperationException {

        Field field = CycleEntity.class.getDeclaredField(
                "parentCycleId"
        );

        field.setAccessible(true);
        field.set(entity, parentId);
    }

    /**
     * Comprueba que un INSERT JPA ignore
     * parent_cycle_id aunque el atributo
     * tenga un valor en memoria.
     */
    @Test
    @DisplayName(
            "Debe ignorar parent_cycle_id durante una inserción JPA"
    )
    void shouldIgnoreParentDuringJpaInsert()
            throws ReflectiveOperationException {

        UUID systemId = createSystem();
        UUID parentId = UUID.randomUUID();

        CycleEntity entity = new CycleEntity(systemId);

        setParentForTest(entity, parentId);

        entityManager.persist(entity);
        entityManager.flush();

        UUID cycleId = entity.getCycleId();

        assertNull(
                findParent(cycleId),
                "La inserción JPA no puede establecer el padre"
        );

        entityManager.clear();

        CycleEntity persisted = entityManager.find(
                CycleEntity.class,
                cycleId
        );

        assertNotNull(persisted);
        assertNull(persisted.getParentCycleId());
    }

    /**
     * Comprueba que un UPDATE JPA ignore
     * una modificación interna del padre,
     * incluso cuando otro campo sí cambia.
     */
    @Test
    @DisplayName(
            "Debe ignorar parent_cycle_id durante una actualización JPA"
    )
    void shouldIgnoreParentDuringJpaUpdate()
            throws ReflectiveOperationException {

        UUID systemId = createSystem();

        CycleEntity parent = createCycle(systemId);
        CycleEntity child = createCycle(systemId);

        UUID childId = child.getCycleId();

        setParentForTest(child, parent.getCycleId());

        child.state("E01_INICIALIZANDO");

        entityManager.flush();

        assertNull(
                findParent(childId),
                "Hibernate no debe actualizar parent_cycle_id"
        );

        entityManager.clear();

        CycleEntity persisted = entityManager.find(
                CycleEntity.class,
                childId
        );

        assertNotNull(persisted);
        assertNull(persisted.getParentCycleId());

        assertEquals(
                "E01_INICIALIZANDO",
                persisted.getState()
        );
    }

    /**
     * Comprueba que el servicio de jerarquía
     * pueda asignar el padre y que JPA
     * posteriormente recupere la relación.
     *
     * <p>Se limpia el contexto de persistencia
     * para evitar valores en memoria desactualizados
     * después de una modificación JDBC.
     */
    @Test
    @DisplayName(
            "Debe permitir modificar la jerarquía mediante CycleHierarchyService"
    )
    void shouldAssignParentOnlyThroughHierarchyService() {

        UUID systemId = createSystem();

        CycleEntity parent = createCycle(systemId);
        CycleEntity child = createCycle(systemId);

        UUID parentId = parent.getCycleId();
        UUID childId = child.getCycleId();

        entityManager.clear();

        hierarchyService.assignParent(
                childId,
                parentId
        );

        assertEquals(
                parentId,
                findParent(childId)
        );

        entityManager.clear();

        CycleEntity persisted = entityManager.find(
                CycleEntity.class,
                childId
        );

        assertNotNull(persisted);
        assertEquals(
                parentId,
                persisted.getParentCycleId()
        );
    }
}

