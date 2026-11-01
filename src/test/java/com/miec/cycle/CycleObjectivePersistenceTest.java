
package com.miec.cycle;

import com.miec.objective.ObjectiveEntity;
import com.miec.system.SystemObjectiveEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de la asociación ciclo-objetivo.
 *
 * <p>Verifica la persistencia real de las entidades:
 * {@link SystemObjectiveEntity},
 * {@link CycleEntity},
 * {@link ObjectiveEntity} y
 * {@link CycleObjectiveEntity}.
 *
 * <p>Las pruebas se ejecutan sobre H2 en memoria,
 * utilizando las migraciones Flyway V001 y V002.
 *
 * <p>Reglas verificadas:
 * <ul>
 *   <li>IR-001: ciclo y objetivo pertenecen al
 *       mismo sistema.</li>
 *   <li>La clave primaria compuesta identifica
 *       una única asociación.</li>
 *   <li>Las claves foráneas rechazan asociaciones
 *       con sistemas incompatibles.</li>
 *   <li>La asociación puede recuperarse mediante JPA.</li>
 * </ul>
 *
 * <p>Cada prueba se ejecuta dentro de una transacción
 * que Spring revierte automáticamente al finalizar.
 * De esta manera se mantiene el aislamiento de datos.
 *
 * <p>Se utiliza SpringBootTest para cargar el contexto
 * completo de persistencia y Flyway de Spring Boot 4.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_cycle_objective_it;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 - Integración JPA y Flyway de ciclo-objetivo"
)
class CycleObjectivePersistenceTest {

    /**
     * Administrador JPA utilizado para persistir
     * y recuperar las entidades del modelo.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * Permite verificar directamente las restricciones SQL
     * implementadas mediante Flyway V002.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Crea y persiste un sistema objetivo de prueba.
     *
     * @param name nombre del sistema
     * @return sistema persistido con identificador UUID
     */
    private SystemObjectiveEntity createSystem(String name) {
        SystemObjectiveEntity system =
                new SystemObjectiveEntity(name);

        entityManager.persist(system);
        entityManager.flush();

        assertNotNull(system.getSystemId());

        return system;
    }

    /**
     * Crea y persiste un ciclo para un sistema existente.
     *
     * @param systemId identificador del sistema propietario
     * @return ciclo persistido
     */
    private CycleEntity createCycle(UUID systemId) {
        CycleEntity cycle = new CycleEntity(systemId);

        entityManager.persist(cycle);
        entityManager.flush();

        assertNotNull(cycle.getCycleId());

        return cycle;
    }

    /**
     * Crea y persiste un objetivo para un sistema existente.
     *
     * @param systemId identificador del sistema propietario
     * @return objetivo persistido
     */
    private ObjectiveEntity createObjective(UUID systemId) {
        ObjectiveEntity objective =
                new ObjectiveEntity(
                        systemId,
                        "Objetivo de integración MIEC"
                );

        entityManager.persist(objective);
        entityManager.flush();

        assertNotNull(objective.getObjectiveId());

        return objective;
    }

    /**
     * Inserta una asociación mediante JPA y fuerza
     * su escritura en la base de datos.
     *
     * @param cycleId identificador del ciclo
     * @param objectiveId identificador del objetivo
     * @param systemId identificador común del sistema
     * @return asociación persistida
     */
    private CycleObjectiveEntity createAssociation(
            UUID cycleId,
            UUID objectiveId,
            UUID systemId
    ) {
        CycleObjectiveEntity association =
                new CycleObjectiveEntity(
                        cycleId,
                        objectiveId,
                        systemId
                );

        entityManager.persist(association);
        entityManager.flush();

        return association;
    }

    /**
     * Comprueba que las entidades principales puedan
     * persistirse usando el esquema migrado.
     */
    @Test
    @DisplayName(
            "Debe persistir un sistema, un ciclo y un objetivo"
    )
    void shouldPersistSystemCycleAndObjective() {
        SystemObjectiveEntity system =
                createSystem("Sistema MIEC");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        ObjectiveEntity objective =
                createObjective(system.getSystemId());

        assertNotNull(system.getSystemId());
        assertNotNull(cycle.getCycleId());
        assertNotNull(objective.getObjectiveId());

        assertEquals(
                system.getSystemId(),
                cycle.getSystemId()
        );

        assertEquals(
                system.getSystemId(),
                objective.getSystemId()
        );
    }

    /**
     * Verifica que una asociación válida se inserte
     * en la tabla cycle_objective.
     */
    @Test
    @DisplayName(
            "Debe persistir la asociación de un ciclo y un objetivo del mismo sistema"
    )
    void shouldPersistValidAssociation() {
        SystemObjectiveEntity system =
                createSystem("Sistema asociado");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        ObjectiveEntity objective =
                createObjective(system.getSystemId());

        createAssociation(
                cycle.getCycleId(),
                objective.getObjectiveId(),
                system.getSystemId()
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM cycle_objective
                WHERE cycle_id = ?
                  AND objective_id = ?
                  AND system_id = ?
                """,
                Integer.class,
                cycle.getCycleId(),
                objective.getObjectiveId(),
                system.getSystemId()
        );

        assertEquals(1, count);
    }

    /**
     * Comprueba que Hibernate pueda recuperar
     * una asociación mediante su clave compuesta.
     */
    @Test
    @DisplayName(
            "Debe recuperar una asociación mediante su clave primaria compuesta"
    )
    void shouldFindAssociationByCompositeId() {
        SystemObjectiveEntity system =
                createSystem("Sistema consultable");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        ObjectiveEntity objective =
                createObjective(system.getSystemId());

        createAssociation(
                cycle.getCycleId(),
                objective.getObjectiveId(),
                system.getSystemId()
        );

        CycleObjectiveId id = new CycleObjectiveId(
                cycle.getCycleId(),
                objective.getObjectiveId()
        );

        entityManager.clear();

        CycleObjectiveEntity recovered =
                entityManager.find(
                        CycleObjectiveEntity.class,
                        id
                );

        assertNotNull(recovered);
        assertEquals(
                system.getSystemId(),
                recovered.getSystemId()
        );
        assertEquals(
                cycle.getCycleId(),
                recovered.getCycleId()
        );
        assertEquals(
                objective.getObjectiveId(),
                recovered.getObjectiveId()
        );
    }

    /**
     * Comprueba IR-001: no es válido asociar
     * un objetivo de otro sistema.
     *
     * <p>Se realiza una inserción directa por JDBC para
     * comprobar el rechazo de la clave foránea compuesta,
     * sin dejar el contexto JPA en estado inconsistente.
     */
    @Test
    @DisplayName(
            "Debe rechazar un objetivo que pertenece a un sistema diferente"
    )
    void shouldRejectObjectiveFromAnotherSystem() {
        SystemObjectiveEntity systemA =
                createSystem("Sistema A");

        SystemObjectiveEntity systemB =
                createSystem("Sistema B");

        CycleEntity cycleA =
                createCycle(systemA.getSystemId());

        ObjectiveEntity objectiveB =
                createObjective(systemB.getSystemId());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO cycle_objective
                            (cycle_id, objective_id, system_id)
                        VALUES (?, ?, ?)
                        """,
                        cycleA.getCycleId(),
                        objectiveB.getObjectiveId(),
                        systemA.getSystemId()
                )
        );
    }

    /**
     * Comprueba IR-001 desde la referencia del ciclo.
     *
     * <p>La asociación debe rechazarse cuando el sistema
     * informado corresponde al objetivo, pero no al ciclo.
     */
    @Test
    @DisplayName(
            "Debe rechazar una asociación cuyo sistema no corresponde al ciclo"
    )
    void shouldRejectCycleFromAnotherSystem() {
        SystemObjectiveEntity systemA =
                createSystem("Sistema origen");

        SystemObjectiveEntity systemB =
                createSystem("Sistema destino");

        CycleEntity cycleA =
                createCycle(systemA.getSystemId());

        ObjectiveEntity objectiveB =
                createObjective(systemB.getSystemId());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO cycle_objective
                            (cycle_id, objective_id, system_id)
                        VALUES (?, ?, ?)
                        """,
                        cycleA.getCycleId(),
                        objectiveB.getObjectiveId(),
                        systemB.getSystemId()
                )
        );
    }

    /**
     * Comprueba que la clave primaria compuesta
     * impida duplicar una asociación existente.
     */
    @Test
    @DisplayName(
            "Debe rechazar una asociación duplicada entre ciclo y objetivo"
    )
    void shouldRejectDuplicateAssociation() {
        SystemObjectiveEntity system =
                createSystem("Sistema único");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        ObjectiveEntity objective =
                createObjective(system.getSystemId());

        createAssociation(
                cycle.getCycleId(),
                objective.getObjectiveId(),
                system.getSystemId()
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO cycle_objective
                            (cycle_id, objective_id, system_id)
                        VALUES (?, ?, ?)
                        """,
                        cycle.getCycleId(),
                        objective.getObjectiveId(),
                        system.getSystemId()
                )
        );
    }
}

