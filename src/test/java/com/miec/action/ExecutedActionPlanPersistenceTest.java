
package com.miec.action;

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
 * Pruebas de integración de IR-005 para MIEC-F03.
 *
 * <p>Verifica que toda acción ejecutada pertenezca
 * al mismo ciclo que su plan de intervención.
 *
 * <p>Flyway V002 protege esta regla mediante la
 * restricción FK_ACTION_PLAN_CYCLE, compuesta por
 * las columnas plan_id y cycle_id.
 *
 * <p>Estas pruebas utilizan:
 * <ul>
 *   <li>Spring Boot para inicializar el contexto.</li>
 *   <li>H2 en memoria para aislar los datos.</li>
 *   <li>Flyway V001/V002 para construir el esquema.</li>
 *   <li>JdbcTemplate para verificar la integridad SQL.</li>
 *   <li>JUnit 5 para las aserciones.</li>
 * </ul>
 *
 * <p>Cada prueba se ejecuta transaccionalmente,
 * permitiendo revertir sus registros.
 *
 * <p>No se crean entidades JPA adicionales.
 *
 * <p>Esta clase no valida IR-009, relacionada
 * con la autorización previa a la ejecución.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir005_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-005 - Integridad entre acción ejecutada, plan y ciclo"
)
class ExecutedActionPlanPersistenceTest {

    /** Cliente JDBC para las operaciones de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Crea un sistema objetivo válido.
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
                        "Sistema de validación IR-005"
                )
        );

        return systemId;
    }

    /**
     * Crea un ciclo perteneciente al sistema indicado.
     *
     * @param systemId identificador del sistema
     * @return identificador del ciclo
     */
    private UUID createCycle(UUID systemId) {
        UUID cycleId = UUID.randomUUID();

        assertEquals(
                1,
                jdbcTemplate.update(
                        """
                        INSERT INTO cycle (
                            cycle_id,
                            system_id
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
     * Crea un plan de intervención dentro de un ciclo.
     *
     * <p>El estado PROPOSED se utiliza únicamente
     * como dato válido para la columna status.
     * IR-005 no evalúa la aprobación del plan.
     *
     * @param cycleId identificador del ciclo
     * @return identificador del plan
     */
    private UUID createPlan(UUID cycleId) {
        UUID planId = UUID.randomUUID();

        assertEquals(
                1,
                jdbcTemplate.update(
                        """
                        INSERT INTO intervention_plan (
                            plan_id,
                            cycle_id,
                            status
                        )
                        VALUES (?, ?, ?)
                        """,
                        planId,
                        cycleId,
                        "PROPOSED"
                )
        );

        return planId;
    }

    /**
     * Inserta una acción ejecutada.
     *
     * <p>Los campos obligatorios action_type
     * y execution_status se proporcionan según V001.
     *
     * <p>La verificación entre plan_id y cycle_id
     * corresponde a la base de datos.
     *
     * @param actionId identificador de la acción
     * @param planId plan asociado
     * @param cycleId ciclo asociado
     * @return número de filas insertadas
     */
    private int insertAction(
            UUID actionId,
            UUID planId,
            UUID cycleId
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO executed_action (
                    action_id,
                    plan_id,
                    cycle_id,
                    action_type,
                    execution_status
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                actionId,
                planId,
                cycleId,
                "PROCESS_EXECUTION",
                "COMPLETED"
        );
    }

    /**
     * Cuenta las acciones que coinciden
     * con el identificador proporcionado.
     *
     * @param actionId identificador de acción
     * @return cantidad de registros encontrados
     */
    private int countAction(UUID actionId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM executed_action
                WHERE action_id = ?
                """,
                Integer.class,
                actionId
        );

        return Objects.requireNonNull(count);
    }

    /**
     * Verifica que una acción pueda registrarse
     * cuando utiliza el mismo ciclo que su plan.
     */
    @Test
    @DisplayName(
            "Debe aceptar una acción cuyo plan pertenece al mismo ciclo"
    )
    void shouldAcceptActionWithPlanFromSameCycle() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId);
        UUID planId = createPlan(cycleId);

        UUID actionId = UUID.randomUUID();

        assertEquals(
                1,
                insertAction(actionId, planId, cycleId)
        );

        assertEquals(1, countAction(actionId));

        UUID persistedCycleId = jdbcTemplate.queryForObject(
                """
                SELECT cycle_id
                FROM executed_action
                WHERE action_id = ?
                """,
                UUID.class,
                actionId
        );

        assertEquals(cycleId, persistedCycleId);
    }

    /**
     * Verifica el rechazo de una acción asociada
     * a un plan perteneciente a otro ciclo.
     *
     * <p>Ambos ciclos y el plan existen.
     * La única incompatibilidad es la combinación
     * de plan_id y cycle_id, aislando IR-005.
     */
    @Test
    @DisplayName(
            "Debe rechazar una acción asociada a un plan de otro ciclo"
    )
    void shouldRejectActionWithPlanFromDifferentCycle() {
        UUID systemId = createSystem();

        UUID cycleA = createCycle(systemId);
        UUID cycleB = createCycle(systemId);

        UUID planB = createPlan(cycleB);
        UUID actionId = UUID.randomUUID();

        assertNotEquals(cycleA, cycleB);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAction(
                        actionId,
                        planB,
                        cycleA
                )
        );

        assertEquals(0, countAction(actionId));
    }

    /**
     * Verifica que una acción no pueda referenciar
     * un plan inexistente, incluso si su ciclo existe.
     */
    @Test
    @DisplayName(
            "Debe rechazar una acción cuyo plan no existe"
    )
    void shouldRejectActionWithUnknownPlan() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId);

        UUID unknownPlanId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAction(
                        actionId,
                        unknownPlanId,
                        cycleId
                )
        );

        assertEquals(0, countAction(actionId));
    }

    /**
     * Verifica que una acción no pueda referenciar
     * un ciclo inexistente, aunque el plan sí exista.
     */
    @Test
    @DisplayName(
            "Debe rechazar una acción cuyo ciclo no existe"
    )
    void shouldRejectActionWithUnknownCycle() {
        UUID systemId = createSystem();
        UUID validCycleId = createCycle(systemId);
        UUID planId = createPlan(validCycleId);

        UUID unknownCycleId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAction(
                        actionId,
                        planId,
                        unknownCycleId
                )
        );

        assertEquals(0, countAction(actionId));
    }

    /**
     * Comprueba que un plan pueda contener
     * varias acciones ejecutadas dentro del mismo ciclo.
     *
     * <p>IR-005 no establece unicidad sobre plan_id
     * o sobre la combinación (plan_id, cycle_id).
     */
    @Test
    @DisplayName(
            "Debe permitir varias acciones vinculadas al mismo plan y ciclo"
    )
    void shouldAllowMultipleActionsForSamePlanAndCycle() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId);
        UUID planId = createPlan(cycleId);

        UUID firstActionId = UUID.randomUUID();
        UUID secondActionId = UUID.randomUUID();

        assertEquals(
                1,
                insertAction(
                        firstActionId,
                        planId,
                        cycleId
                )
        );

        assertEquals(
                1,
                insertAction(
                        secondActionId,
                        planId,
                        cycleId
                )
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM executed_action
                WHERE plan_id = ?
                  AND cycle_id = ?
                """,
                Integer.class,
                planId,
                cycleId
        );

        assertEquals(2, count);

        assertAll(
                () -> assertEquals(
                        1,
                        countAction(firstActionId)
                ),
                () -> assertEquals(
                        1,
                        countAction(secondActionId)
                )
        );
    }
}

