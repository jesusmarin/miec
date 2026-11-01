
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
 * Pruebas de integración de la regla IR-011 de MIEC-F03.
 *
 * <p>Garantiza que un ciclo hijo solamente pueda
 * referenciar un ciclo padre perteneciente al
 * mismo sistema objetivo.
 *
 * <p>La integridad está protegida por la restricción
 * FK_CYCLE_PARENT_SYSTEM, definida en Flyway V002:
 *
 * <pre>
 * FOREIGN KEY (parent_cycle_id, system_id)
 * REFERENCES cycle (cycle_id, system_id)
 * </pre>
 *
 * <p>Se verifican inserciones, actualizaciones,
 * referencias inexistentes y ciclos independientes.
 *
 * <p>Las pruebas usan H2 en memoria y JDBC directo,
 * sin introducir entidades JPA adicionales.
 *
 * <p>IR-012 (autorreferencia) e IR-013 (ciclos
 * jerárquicos indirectos) se validan por separado.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir011_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-011 - Integridad entre ciclo padre y sistema"
)
class CycleParentSystemPersistenceTest {

    /** Acceso JDBC a la base de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Crea un sistema objetivo.
     *
     * @param name nombre del sistema
     * @return identificador generado
     */
    private UUID createSystem(String name) {
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
                        name
                )
        );

        return systemId;
    }

    /**
     * Inserta un ciclo dentro de un sistema.
     *
     * @param cycleId identificador del ciclo
     * @param systemId sistema propietario
     * @param parentCycleId padre opcional
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
     * Crea un ciclo válido y devuelve su identificador.
     *
     * @param systemId sistema propietario
     * @param parentCycleId ciclo padre o null
     * @return identificador del ciclo creado
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
     * Cuenta los ciclos con el identificador indicado.
     *
     * @param cycleId identificador consultado
     * @return número de ciclos encontrados
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
     * Consulta el padre persistido de un ciclo.
     *
     * @param cycleId identificador del ciclo
     * @return identificador del padre, o null
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
     * Verifica que un hijo pueda referenciar
     * un ciclo padre del mismo sistema.
     */
    @Test
    @DisplayName(
            "Debe aceptar un ciclo hijo cuyo padre pertenece al mismo sistema"
    )
    void shouldAcceptParentFromSameSystem() {
        UUID systemId = createSystem("Sistema A");

        UUID parentId = createCycle(
                systemId,
                null
        );

        UUID childId = createCycle(
                systemId,
                parentId
        );

        assertAll(
                () -> assertEquals(
                        1,
                        countCycle(childId)
                ),
                () -> assertEquals(
                        parentId,
                        findParent(childId)
                )
        );
    }

    /**
     * Comprueba que un ciclo no pueda tener
     * un padre perteneciente a otro sistema.
     *
     * <p>El padre existe, por lo que el rechazo
     * corresponde a la incompatibilidad de
     * (parent_cycle_id, system_id).
     */
    @Test
    @DisplayName(
            "Debe rechazar un padre perteneciente a otro sistema"
    )
    void shouldRejectParentFromDifferentSystem() {
        UUID systemA = createSystem("Sistema A");
        UUID systemB = createSystem("Sistema B");

        UUID parentB = createCycle(
                systemB,
                null
        );

        UUID childA = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertCycle(
                        childA,
                        systemA,
                        parentB
                )
        );

        assertEquals(0, countCycle(childA));
    }

    /**
     * Verifica que no pueda referenciarse
     * un ciclo padre inexistente.
     */
    @Test
    @DisplayName(
            "Debe rechazar un ciclo padre inexistente"
    )
    void shouldRejectUnknownParent() {
        UUID systemId = createSystem("Sistema A");

        UUID unknownParentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertCycle(
                        childId,
                        systemId,
                        unknownParentId
                )
        );

        assertEquals(0, countCycle(childId));
    }

    /**
     * Comprueba que un ciclo pueda existir
     * sin estar subordinado a otro ciclo.
     *
     * <p>La relación parent_cycle_id es opcional
     * en Flyway V001.
     */
    @Test
    @DisplayName(
            "Debe permitir ciclos independientes sin padre"
    )
    void shouldAllowCycleWithoutParent() {
        UUID systemId = createSystem("Sistema A");

        UUID cycleId = createCycle(
                systemId,
                null
        );

        assertEquals(1, countCycle(cycleId));
        assertNull(findParent(cycleId));
    }

    /**
     * Verifica que la restricción también
     * protege actualizaciones de parent_cycle_id.
     *
     * <p>La operación intenta asignar a un ciclo
     * existente un padre de otro sistema.
     */
    @Test
    @DisplayName(
            "Debe rechazar la actualización hacia un padre de otro sistema"
    )
    void shouldRejectUpdateToParentFromDifferentSystem() {
        UUID systemA = createSystem("Sistema A");
        UUID systemB = createSystem("Sistema B");

        UUID childA = createCycle(
                systemA,
                null
        );

        UUID parentB = createCycle(
                systemB,
                null
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE cycle
                        SET parent_cycle_id = ?
                        WHERE cycle_id = ?
                        """,
                        parentB,
                        childA
                )
        );

        assertNull(findParent(childA));
    }

    /**
     * Comprueba que un ciclo pueda adoptar
     * un padre válido de su mismo sistema.
     *
     * <p>Se verifica la actualización del vínculo
     * sin modificar la pertenencia del ciclo.
     */
    @Test
    @DisplayName(
            "Debe permitir actualizar un ciclo con un padre del mismo sistema"
    )
    void shouldAllowUpdateToParentFromSameSystem() {
        UUID systemId = createSystem("Sistema A");

        UUID childId = createCycle(
                systemId,
                null
        );

        UUID parentId = createCycle(
                systemId,
                null
        );

        assertNotEquals(childId, parentId);

        assertEquals(
                1,
                jdbcTemplate.update(
                        """
                        UPDATE cycle
                        SET parent_cycle_id = ?
                        WHERE cycle_id = ?
                        """,
                        parentId,
                        childId
                )
        );

        assertEquals(
                parentId,
                findParent(childId)
        );
    }
}

