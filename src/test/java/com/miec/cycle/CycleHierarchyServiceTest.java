
package com.miec.cycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración del servicio de jerarquía MIEC.
 *
 * <p>Verifican IR-013 y su compatibilidad con
 * IR-011 e IR-012.
 *
 * <p>Se utiliza H2 aislada con las migraciones
 * Flyway existentes.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir013_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-013 - Prevención de ciclos jerárquicos indirectos"
)
class CycleHierarchyServiceTest {

    /** Servicio sometido a prueba. */
    @Autowired
    private CycleHierarchyService hierarchyService;

    /** Acceso JDBC para preparar y comprobar datos. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                        "Sistema IR-013"
                )
        );

        return systemId;
    }

    /**
     * Crea un ciclo raíz.
     *
     * @param systemId sistema propietario
     * @return identificador del ciclo
     */
    private UUID createCycle(UUID systemId) {
        UUID cycleId = UUID.randomUUID();

        assertEquals(
                1,
                jdbcTemplate.update(
                        """
                        INSERT INTO cycle (
                            cycle_id, system_id
                        )
                        VALUES (?, ?)
                        """,
                        cycleId,
                        systemId
                )
        );

        return cycleId;
    }

    /**
     * Consulta el padre actual de un ciclo.
     *
     * @param cycleId identificador del ciclo
     * @return identificador del padre o null
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
     * Consulta la versión de concurrencia.
     *
     * @param cycleId identificador del ciclo
     * @return versión persistida
     */
    private long findVersion(UUID cycleId) {
        Long version = jdbcTemplate.queryForObject(
                """
                SELECT version
                FROM cycle
                WHERE cycle_id = ?
                """,
                Long.class,
                cycleId
        );

        return Objects.requireNonNull(version);
    }

    /**
     * Verifica una asignación válida.
     */
    @Test
    @DisplayName(
            "Debe permitir asignar un padre del mismo sistema"
    )
    void shouldAssignValidParent() {
        UUID systemId = createSystem();

        UUID parent = createCycle(systemId);
        UUID child = createCycle(systemId);

        hierarchyService.assignParent(child, parent);

        assertEquals(parent, findParent(child));
        assertEquals(1L, findVersion(child));
    }

    /**
     * Comprueba la aceptación de una jerarquía
     * válida con varios niveles.
     */
    @Test
    @DisplayName(
            "Debe permitir una jerarquía de tres niveles sin circularidad"
    )
    void shouldAllowValidThreeLevelHierarchy() {
        UUID systemId = createSystem();

        UUID root = createCycle(systemId);
        UUID middle = createCycle(systemId);
        UUID leaf = createCycle(systemId);

        hierarchyService.assignParent(middle, root);
        hierarchyService.assignParent(leaf, middle);

        assertAll(
                () -> assertNull(findParent(root)),
                () -> assertEquals(root, findParent(middle)),
                () -> assertEquals(middle, findParent(leaf))
        );
    }

    /**
     * Verifica la prohibición de autorreferencia
     * directa mediante el servicio.
     */
    @Test
    @DisplayName(
            "Debe rechazar que un ciclo sea su propio padre"
    )
    void shouldRejectDirectSelfReference() {
        UUID systemId = createSystem();
        UUID cycle = createCycle(systemId);

        assertThrows(
                IllegalArgumentException.class,
                () -> hierarchyService.assignParent(
                        cycle, cycle
                )
        );

        assertNull(findParent(cycle));
        assertEquals(0L, findVersion(cycle));
    }

    /**
     * Verifica la prevención de una relación
     * circular formada por dos ciclos.
     */
    @Test
    @DisplayName(
            "Debe rechazar una relación circular entre dos ciclos"
    )
    void shouldRejectTwoNodeCycle() {
        UUID systemId = createSystem();

        UUID cycleA = createCycle(systemId);
        UUID cycleB = createCycle(systemId);

        hierarchyService.assignParent(cycleB, cycleA);

        assertThrows(
                IllegalArgumentException.class,
                () -> hierarchyService.assignParent(
                        cycleA, cycleB
                )
        );

        assertAll(
                () -> assertNull(findParent(cycleA)),
                () -> assertEquals(cycleA, findParent(cycleB))
        );
    }

    /**
     * Verifica IR-013 para una jerarquía
     * circular indirecta con tres ciclos.
     */
    @Test
    @DisplayName(
            "Debe rechazar una jerarquía circular A-B-C-A"
    )
    void shouldRejectThreeNodeCycle() {
        UUID systemId = createSystem();

        UUID cycleA = createCycle(systemId);
        UUID cycleB = createCycle(systemId);
        UUID cycleC = createCycle(systemId);

        hierarchyService.assignParent(cycleB, cycleA);
        hierarchyService.assignParent(cycleC, cycleB);

        assertThrows(
                IllegalArgumentException.class,
                () -> hierarchyService.assignParent(
                        cycleA, cycleC
                )
        );

        assertAll(
                () -> assertNull(findParent(cycleA)),
                () -> assertEquals(cycleA, findParent(cycleB)),
                () -> assertEquals(cycleB, findParent(cycleC)),
                () -> assertEquals(0L, findVersion(cycleA))
        );
    }

    /**
     * Verifica que el servicio también preserve
     * la pertenencia al sistema de IR-011.
     */
    @Test
    @DisplayName(
            "Debe rechazar un padre perteneciente a otro sistema"
    )
    void shouldRejectParentFromAnotherSystem() {
        UUID systemA = createSystem();
        UUID systemB = createSystem();

        UUID child = createCycle(systemA);
        UUID parent = createCycle(systemB);

        assertThrows(
                IllegalArgumentException.class,
                () -> hierarchyService.assignParent(
                        child, parent
                )
        );

        assertNull(findParent(child));
    }

    /**
     * Comprueba el rechazo de referencias
     * a ciclos padre inexistentes.
     */
    @Test
    @DisplayName(
            "Debe rechazar un ciclo padre inexistente"
    )
    void shouldRejectUnknownParent() {
        UUID systemId = createSystem();
        UUID child = createCycle(systemId);

        assertThrows(
                IllegalArgumentException.class,
                () -> hierarchyService.assignParent(
                        child, UUID.randomUUID()
                )
        );

        assertNull(findParent(child));
    }

    /**
     * Comprueba que pueda eliminarse una relación
     * parental sin borrar el ciclo.
     */
    @Test
    @DisplayName(
            "Debe permitir retirar el padre de un ciclo"
    )
    void shouldRemoveParent() {
        UUID systemId = createSystem();

        UUID parent = createCycle(systemId);
        UUID child = createCycle(systemId);

        hierarchyService.assignParent(child, parent);
        assertEquals(parent, findParent(child));

        hierarchyService.assignParent(child, null);

        assertNull(findParent(child));
        assertEquals(2L, findVersion(child));
    }
}
