
package com.miec.cycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de IR-012 para MIEC-F03.
 *
 * <p>Regla de integridad:
 * un ciclo no puede identificarse como su propio padre.
 *
 * <p>Flyway V002 implementa esta regla mediante
 * CK_CYCLE_NOT_SELF_PARENT:
 *
 * <pre>
 * CHECK (
 *     parent_cycle_id IS NULL
 *     OR parent_cycle_id <> cycle_id
 * )
 * </pre>
 *
 * <p>Esta clase verifica:
 * <ul>
 *   <li>El rechazo de autorreferencias al insertar.</li>
 *   <li>El rechazo de autorreferencias al actualizar.</li>
 *   <li>La aceptación de ciclos sin padre.</li>
 *   <li>La aceptación de padres diferentes del hijo.</li>
 *   <li>La conservación del vínculo tras una operación rechazada.</li>
 *   <li>La eliminación válida de una referencia parental.</li>
 * </ul>
 *
 * <p>Se utiliza JDBC para comprobar directamente
 * las restricciones de Flyway V001/V002 sobre H2.
 *
 * <p>IR-013, relacionada con los ciclos jerárquicos
 * indirectos, no forma parte de estas pruebas.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir012_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-012 - Prevención de autorreferencia de ciclos"
)
class CycleSelfParentPersistenceTest {

    /** Acceso JDBC a la base de integración. */
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
                            system_id,
                            name
                        )
                        VALUES (?, ?)
                        """,
                        systemId,
                        "Sistema de validación IR-012"
                )
        );

        return systemId;
    }

    /**
     * Inserta un ciclo con una referencia parental
     * opcional.
     *
     * @param cycleId identificador del ciclo
     * @param systemId identificador del sistema
     * @param parentCycleId padre o null
     * @return número de filas insertadas
     */
    private int insertCycle(
            UUID cycleId,
            UUID systemId,
            UUID parentCycleId
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO cycle (
                    cycle_id,
                    system_id,
                    parent_cycle_id
                )
                VALUES (?, ?, ?)
                """,
                cycleId,
                systemId,
                parentCycleId
        );
    }

    /**
     * Crea un ciclo válido.
     *
     * @param systemId sistema propietario
     * @param parentCycleId padre opcional
     * @return identificador del ciclo
     */
    private UUID createCycle(
            UUID systemId,
            UUID parentCycleId
    ) {
        UUID cycleId = UUID.randomUUID();

        assertEquals(
                1,
                insertCycle(
                        cycleId,
                        systemId,
                        parentCycleId
                )
        );

        return cycleId;
    }

    /**
     * Consulta la referencia parental de un ciclo.
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
     * Cuenta los ciclos con el identificador indicado.
     *
     * @param cycleId identificador consultado
     * @return cantidad de ciclos encontrados
     */
    private int countCycle(UUID cycleId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM cycle
                WHERE cycle_id = ?
                """,
                Integer.class,
                cycleId
        );

        return Objects.requireNonNull(count);
    }

    /**
     * Comprueba que una inserción no pueda establecer
     * el mismo UUID como identificador del ciclo y padre.
     *
     * <p>El rechazo debe producirse por
     * CK_CYCLE_NOT_SELF_PARENT.
     */
    @Test
    @DisplayName(
            "Debe rechazar la inserción de un ciclo que es su propio padre"
    )
    void shouldRejectSelfParentDuringInsert() {
        UUID systemId = createSystem();
        UUID cycleId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertCycle(
                        cycleId,
                        systemId,
                        cycleId
                )
        );

        assertEquals(0, countCycle(cycleId));
    }

    /**
     * Verifica que un ciclo existente sin padre
     * no pueda actualizarse para apuntarse a sí mismo.
     */
    @Test
    @DisplayName(
            "Debe rechazar una actualización que asigna al ciclo como su propio padre"
    )
    void shouldRejectSelfParentDuringUpdate() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId, null);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE cycle
                        SET parent_cycle_id = ?
                        WHERE cycle_id = ?
                        """,
                        cycleId,
                        cycleId
                )
        );

        assertNull(findParent(cycleId));
    }

    /**
     * Comprueba la aceptación de un ciclo raíz
     * cuya referencia parental es nula.
     */
    @Test
    @DisplayName(
            "Debe permitir crear un ciclo raíz sin padre"
    )
    void shouldAllowRootCycleWithoutParent() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId, null);

        assertEquals(1, countCycle(cycleId));
        assertNull(findParent(cycleId));
    }

    /**
     * Comprueba que un ciclo pueda tener un padre
     * diferente, perteneciente al mismo sistema.
     *
     * <p>Este escenario respeta simultáneamente
     * IR-011 e IR-012.
     */
    @Test
    @DisplayName(
            "Debe permitir un ciclo padre diferente perteneciente al mismo sistema"
    )
    void shouldAllowDifferentParentFromSameSystem() {
        UUID systemId = createSystem();

        UUID parentId = createCycle(systemId, null);
        UUID childId = createCycle(systemId, parentId);

        assertNotEquals(parentId, childId);
        assertEquals(parentId, findParent(childId));
    }

    /**
     * Verifica que un intento de autorreferencia
     * no modifique un vínculo parental válido.
     *
     * <p>Primero se crea un padre válido y después
     * se intenta reemplazarlo con el propio hijo.
     */
    @Test
    @DisplayName(
            "Debe conservar el padre anterior cuando se rechaza una autorreferencia"
    )
    void shouldPreserveParentAfterRejectedSelfReference() {
        UUID systemId = createSystem();

        UUID parentId = createCycle(systemId, null);
        UUID childId = createCycle(systemId, parentId);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE cycle
                        SET parent_cycle_id = ?
                        WHERE cycle_id = ?
                        """,
                        childId,
                        childId
                )
        );

        assertEquals(parentId, findParent(childId));
    }

    /**
     * Comprueba que un ciclo pueda dejar de
     * tener padre mediante una actualización.
     *
     * <p>La nulabilidad de parent_cycle_id está
     * permitida por Flyway V001 y por IR-012.
     */
    @Test
    @DisplayName(
            "Debe permitir eliminar la referencia parental de un ciclo"
    )
    void shouldAllowRemovingParentReference() {
        UUID systemId = createSystem();

        UUID parentId = createCycle(systemId, null);
        UUID childId = createCycle(systemId, parentId);

        assertEquals(parentId, findParent(childId));

        assertEquals(
                1,
                jdbcTemplate.update(
                        """
                        UPDATE cycle
                        SET parent_cycle_id = NULL
                        WHERE cycle_id = ?
                        """,
                        childId
                )
        );

        assertNull(findParent(childId));
    }
}

