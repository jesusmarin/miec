
package com.miec.cycle;

import com.miec.system.SystemObjectiveEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import com.miec.audit.CycleEventEntity;

/**
 * Pruebas de integración de StateTransitionEntity.
 *
 * <p>Verifican IR-007 del modelo MIEC-F03:
 * una transición de estado solamente puede referenciar
 * un evento desencadenante perteneciente al mismo ciclo.
 *
 * <p>Se utilizan:
 * <ul>
 *   <li>Spring Boot para inicializar el contexto.</li>
 *   <li>H2 en memoria como base independiente.</li>
 *   <li>Flyway V001 y V002 para crear el esquema.</li>
 *   <li>JPA para persistir y recuperar entidades.</li>
 *   <li>JDBC para provocar y verificar infracciones
 *       de integridad referencial.</li>
 * </ul>
 *
 * <p>Las pruebas son transaccionales. Los cambios
 * realizados en cada prueba se revierten al finalizar.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_state_transition_it;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 ENT-14 - Integración de transiciones e IR-007"
)
class StateTransitionPersistenceTest {

    /** Administrador de persistencia JPA. */
    @Autowired
    private EntityManager entityManager;

    /** Acceso JDBC para consultas y validaciones SQL. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Crea y persiste un sistema objetivo.
     *
     * @param name nombre del sistema
     * @return sistema persistido
     */
    private SystemObjectiveEntity createSystem(String name) {
        SystemObjectiveEntity system =
                new SystemObjectiveEntity(name);

        entityManager.persist(system);
        entityManager.flush();

        return system;
    }

    /**
     * Crea y persiste un ciclo perteneciente
     * a un sistema objetivo.
     *
     * @param systemId identificador del sistema
     * @return ciclo persistido
     */
    private CycleEntity createCycle(UUID systemId) {
        CycleEntity cycle = new CycleEntity(systemId);

        entityManager.persist(cycle);
        entityManager.flush();

        return cycle;
    }

    /**
     * Crea y persiste un evento perteneciente
     * al ciclo indicado.
     *
     * @param cycleId identificador del ciclo
     * @return evento persistido
     */
    private CycleEventEntity createEvent(UUID cycleId) {
        CycleEventEntity event = new CycleEventEntity(
                cycleId,
                "EVALUATION_COMPLETED",
                Instant.now()
        );

        entityManager.persist(event);
        entityManager.flush();

        return event;
    }

    /**
     * Construye una transición válida para un ciclo.
     *
     * <p>El evento desencadenante no se establece
     * inicialmente para poder verificar también
     * su carácter opcional.
     *
     * @param cycleId identificador del ciclo
     * @return transición todavía no persistida
     */
    private StateTransitionEntity createTransition(
            UUID cycleId
    ) {
        return new StateTransitionEntity(
                cycleId,
                "E00_INACTIVO",
                "E01_PREPARACION",
                "COMPLETED",
                Instant.now()
        );
    }

    /**
     * Verifica que una transición pueda referenciar
     * un evento del mismo ciclo.
     *
     * <p>Comprueba tanto la persistencia JPA como
     * los valores almacenados en la base de datos.
     */
    @Test
    @DisplayName(
            "Debe persistir una transición asociada a un evento del mismo ciclo"
    )
    void shouldPersistTransitionWithSameCycleEvent() {
        SystemObjectiveEntity system =
                createSystem("Sistema transición válida");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        CycleEventEntity event =
                createEvent(cycle.getCycleId());

        StateTransitionEntity transition =
                createTransition(cycle.getCycleId())
                        .triggerEventId(event.getEventId());

        entityManager.persist(transition);
        entityManager.flush();

        assertNotNull(transition.getTransitionId());

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM state_transition
                WHERE transition_id = ?
                  AND cycle_id = ?
                  AND trigger_event_id = ?
                """,
                Integer.class,
                transition.getTransitionId(),
                cycle.getCycleId(),
                event.getEventId()
        );

        assertEquals(1, count);
    }

    /**
     * Verifica la recuperación de una transición
     * persistida con su referencia al evento.
     *
     * <p>Se limpia el contexto de persistencia
     * para obligar a Hibernate a consultar H2.
     */
    @Test
    @DisplayName(
            "Debe recuperar mediante JPA una transición y su evento desencadenante"
    )
    void shouldRecoverTransitionWithTriggerEvent() {
        SystemObjectiveEntity system =
                createSystem("Sistema recuperación");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        CycleEventEntity event =
                createEvent(cycle.getCycleId());

        StateTransitionEntity transition =
                createTransition(cycle.getCycleId())
                        .triggerEventId(event.getEventId())
                        .transitionRuleId("MIEC-F01-R001")
                        .authorizationReference("AUTH-001");

        entityManager.persist(transition);
        entityManager.flush();

        UUID transitionId = transition.getTransitionId();

        entityManager.clear();

        StateTransitionEntity recovered =
                entityManager.find(
                        StateTransitionEntity.class,
                        transitionId
                );

        assertNotNull(recovered);

        assertAll(
                () -> assertEquals(
                        cycle.getCycleId(),
                        recovered.getCycleId()
                ),
                () -> assertEquals(
                        event.getEventId(),
                        recovered.getTriggerEventId()
                ),
                () -> assertEquals(
                        "E00_INACTIVO",
                        recovered.getFromState()
                ),
                () -> assertEquals(
                        "E01_PREPARACION",
                        recovered.getToState()
                ),
                () -> assertEquals(
                        "COMPLETED",
                        recovered.getTransitionStatus()
                ),
                () -> assertEquals(
                        "MIEC-F01-R001",
                        recovered.getTransitionRuleId()
                ),
                () -> assertEquals(
                        "AUTH-001",
                        recovered.getAuthorizationReference()
                ),
                () -> assertNotNull(
                        recovered.getCreatedAt()
                )
        );
    }

    /**
     * Verifica que trigger_event_id pueda ser nulo.
     *
     * <p>Esto permite registrar transiciones que
     * no tengan un evento desencadenante explícito,
     * conforme a la nulabilidad definida en V001.
     */
    @Test
    @DisplayName(
            "Debe persistir una transición sin evento desencadenante"
    )
    void shouldPersistTransitionWithoutTriggerEvent() {
        SystemObjectiveEntity system =
                createSystem("Sistema transición sin evento");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        StateTransitionEntity transition =
                createTransition(cycle.getCycleId());

        entityManager.persist(transition);
        entityManager.flush();

        UUID transitionId = transition.getTransitionId();

        entityManager.clear();

        StateTransitionEntity recovered =
                entityManager.find(
                        StateTransitionEntity.class,
                        transitionId
                );

        assertNotNull(recovered);
        assertNull(recovered.getTriggerEventId());
        assertEquals(
                cycle.getCycleId(),
                recovered.getCycleId()
        );
    }

    /**
     * Verifica la restricción IR-007 entre ciclos.
     *
     * <p>Se crean dos ciclos pertenecientes al
     * mismo sistema. El evento pertenece al ciclo B,
     * pero se intenta utilizar como desencadenante
     * de una transición del ciclo A.
     *
     * <p>La inserción debe rechazarse por la clave
     * foránea compuesta FK_TRANSITION_EVENT_CYCLE.
     *
     * <p>Usar ciclos del mismo sistema demuestra
     * que la regla protege específicamente el ciclo,
     * y no solamente la pertenencia al sistema.
     */
    @Test
    @DisplayName(
            "Debe rechazar eventos desencadenantes pertenecientes a otro ciclo"
    )
    void shouldRejectTriggerEventFromDifferentCycle() {
        SystemObjectiveEntity system =
                createSystem("Sistema con dos ciclos");

        CycleEntity cycleA =
                createCycle(system.getSystemId());

        CycleEntity cycleB =
                createCycle(system.getSystemId());

        assertNotEquals(
                cycleA.getCycleId(),
                cycleB.getCycleId()
        );

        CycleEventEntity eventB =
                createEvent(cycleB.getCycleId());

        UUID invalidTransitionId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO state_transition (
                            transition_id,
                            cycle_id,
                            from_state,
                            to_state,
                            trigger_event_id,
                            transition_status,
                            occurred_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        invalidTransitionId,
                        cycleA.getCycleId(),
                        "E00_INACTIVO",
                        "E01_PREPARACION",
                        eventB.getEventId(),
                        "COMPLETED",
                        Timestamp.from(Instant.now())
                )
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM state_transition
                WHERE transition_id = ?
                """,
                Integer.class,
                invalidTransitionId
        );

        assertEquals(
                0,
                count,
                "La transición inválida no debe persistirse"
        );
    }

    /**
     * Verifica que no puedan utilizarse
     * referencias a eventos inexistentes.
     *
     * <p>Se provoca directamente la infracción
     * de la clave foránea mediante JDBC.
     */
    @Test
    @DisplayName(
            "Debe rechazar una transición que referencia un evento inexistente"
    )
    void shouldRejectNonexistentTriggerEvent() {
        SystemObjectiveEntity system =
                createSystem("Sistema evento inexistente");

        CycleEntity cycle =
                createCycle(system.getSystemId());

        UUID unknownEventId = UUID.randomUUID();
        UUID invalidTransitionId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO state_transition (
                            transition_id,
                            cycle_id,
                            from_state,
                            to_state,
                            trigger_event_id,
                            transition_status,
                            occurred_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        invalidTransitionId,
                        cycle.getCycleId(),
                        "E00_INACTIVO",
                        "E01_PREPARACION",
                        unknownEventId,
                        "COMPLETED",
                        Timestamp.from(Instant.now())
                )
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM state_transition
                WHERE transition_id = ?
                """,
                Integer.class,
                invalidTransitionId
        );

        assertEquals(
                0,
                count,
                "Una transición con evento inexistente no debe persistirse"
        );
    }
}
