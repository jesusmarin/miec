
package com.miec.action;

import com.miec.action.AuthorizedActionExecutionService.ExecutionCommand;
import com.miec.action.AuthorizedActionExecutionService.ExecutionDeniedException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración entre el contrato JSON v01,
 * PlannedActionPolicy y AuthorizedActionExecutionService.
 *
 * <p>Verifican que solo las acciones declaradas
 * en el plan puedan llegar al ejecutor.
 *
 * <p>La preparación de datos utiliza H2 con Flyway.
 * Cada prueba se revierte mediante una transacción.
 *
 * <p>Los fallos de planificación deben impedir
 * tanto la ejecución como el registro de inicio.
 */
@SpringBootTest(properties = {
        "spring.datasource.url="
                + "jdbc:h2:mem:miec_ir009_json_v01;"
                + "DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional
@DisplayName(
        "MIEC-F03 IR-009 - Integración del contrato JSON v01"
)
class PlannedActionExecutionIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthorizedActionExecutionService service;

    /** Identificadores propios de un escenario. */
    private record Fixture(
            UUID planId,
            UUID cycleId,
            UUID actionId,
            UUID authorizationId
    ) {
    }

    /**
     * Crea un sistema, ciclo, plan y autorización.
     *
     * <p>La autorización siempre es válida para aislar
     * los resultados del contrato de acciones.
     *
     * @param actionsJson documento JSON que se evaluará
     * @return referencias del escenario
     */
    private Fixture createFixture(String actionsJson) {
        UUID systemId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO system_objective (
                    system_id, name
                ) VALUES (?, ?)
                """,
                systemId,
                "IR-009 JSON v01"
        );

        jdbc.update(
                """
                INSERT INTO cycle (
                    cycle_id, system_id
                ) VALUES (?, ?)
                """,
                cycleId,
                systemId
        );

        jdbc.update(
                """
                INSERT INTO intervention_plan (
                    plan_id, cycle_id, status, actions
                ) VALUES (?, ?, ?, ? FORMAT JSON)
                """,
                planId,
                cycleId,
                "PROPOSED",
                actionsJson
        );

        jdbc.update(
                """
                INSERT INTO authorizations (
                    authorization_id,
                    plan_id,
                    action_id,
                    requested_by,
                    approved_by,
                    scope,
                    status,
                    issued_at,
                    expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                authorizationId,
                planId,
                actionId,
                "requester",
                "approver",
                "EXECUTE_ACTION",
                "APPROVED",
                Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600))
        );

        return new Fixture(
                planId,
                cycleId,
                actionId,
                authorizationId
        );
    }

    /**
     * Construye una solicitud de ejecución
     * correspondiente al escenario.
     */
    private ExecutionCommand command(Fixture fixture) {
        return new ExecutionCommand(
                fixture.actionId(),
                fixture.planId(),
                fixture.cycleId(),
                fixture.authorizationId(),
                "PROCESS_EXECUTION",
                "miec-test-actor"
        );
    }

    /**
     * Construye un documento v01 con una acción.
     *
     * @param actionId identidad planificada
     * @param actionType tipo declarado
     * @return JSON v01
     */
    private String validJson(
            UUID actionId,
            String actionType
    ) {
        return """
                {
                  "schemaVersion": "01",
                  "actions": [
                    {
                      "actionId": "%s",
                      "actionType": "%s"
                    }
                  ]
                }
                """.formatted(actionId, actionType);
    }

    /**
     * Cuenta las ejecuciones persistidas.
     */
    private int countExecutions(UUID actionId) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM executed_action
                WHERE action_id = ?
                """,
                Integer.class,
                actionId
        );
    }

    /**
     * Comprueba que una acción declarada pueda ejecutarse
     * cuando también posee autorización válida.
     */
    @Test
    @DisplayName(
            "Debe ejecutar una acción existente en el contrato JSON v01"
    )
    void shouldExecuteDeclaredAction() {
        UUID actionId = UUID.randomUUID();
        Fixture fixture = createFixture(
                validJson(actionId, "PROCESS_EXECUTION")
        );

        // La autorización de createFixture corresponde
        // a fixture.actionId(), así que se ajusta el
        // documento al identificador de esa acción.
        jdbc.update(
                """
                UPDATE intervention_plan
                SET actions = ? FORMAT JSON
                WHERE plan_id = ?
                """,
                validJson(
                        fixture.actionId(),
                        "PROCESS_EXECUTION"
                ),
                fixture.planId()
        );

        AtomicInteger executions = new AtomicInteger();

        var result = service.executeAuthorized(
                command(fixture),
                executions::incrementAndGet
        );

        assertAll(
                () -> assertEquals(1, executions.get()),
                () -> assertEquals("COMPLETED", result.status()),
                () -> assertEquals(
                        1, countExecutions(fixture.actionId())
                )
        );
    }

    /**
     * Comprueba que una acción ausente del plan
     * no pueda llegar al ejecutor.
     */
    @Test
    @DisplayName(
            "Debe rechazar una acción no declarada en el JSON"
    )
    void shouldRejectUnplannedAction() {
        Fixture fixture = createFixture(
                validJson(
                        UUID.randomUUID(),
                        "PROCESS_EXECUTION"
                )
        );

        assertDeniedWithoutExecution(fixture);
    }

    /**
     * Comprueba la coincidencia entre el tipo
     * solicitado y el tipo declarado.
     */
    @Test
    @DisplayName(
            "Debe rechazar un tipo de acción diferente al planificado"
    )
    void shouldRejectDifferentType() {
        Fixture fixture = createFixture(
                validJson(
                        UUID.randomUUID(),
                        "MEASUREMENT"
                )
        );

        jdbc.update(
                """
                UPDATE intervention_plan
                SET actions = ? FORMAT JSON
                WHERE plan_id = ?
                """,
                validJson(fixture.actionId(), "MEASUREMENT"),
                fixture.planId()
        );

        assertDeniedWithoutExecution(fixture);
    }

    /**
     * Comprueba que una lista vacía
     * no concede permiso para ejecutar.
     */
    @Test
    @DisplayName(
            "Debe rechazar un plan cuya lista de acciones esté vacía"
    )
    void shouldRejectEmptyActions() {
        Fixture fixture = createFixture(
                """
                {
                  "schemaVersion": "01",
                  "actions": []
                }
                """
        );

        assertDeniedWithoutExecution(fixture);
    }

    /**
     * Comprueba el rechazo de una versión
     * no reconocida del contrato.
     */
    @Test
    @DisplayName(
            "Debe rechazar versiones de contrato JSON no admitidas"
    )
    void shouldRejectUnknownJsonVersion() {
        Fixture fixture = createFixture(
                """
                {
                  "schemaVersion": "02",
                  "actions": []
                }
                """
        );

        assertDeniedWithoutExecution(fixture);
    }

    /**
     * Comprueba que un documento JSON válido
     * sintácticamente pero incorrecto
     * estructuralmente sea rechazado.
     */
    @Test
    @DisplayName(
            "Debe rechazar un documento sin arreglo de acciones"
    )
    void shouldRejectMissingActionsArray() {
        Fixture fixture = createFixture(
                """
                {
                  "schemaVersion": "01",
                  "actions": {}
                }
                """
        );

        assertDeniedWithoutExecution(fixture);
    }

    /**
     * Verifica la denegación y la ausencia
     * de efectos de ejecución.
     *
     * @param fixture escenario de prueba
     */
    private void assertDeniedWithoutExecution(
            Fixture fixture
    ) {
        AtomicInteger executions = new AtomicInteger();

        ExecutionDeniedException exception = assertThrows(
                ExecutionDeniedException.class,
                () -> service.executeAuthorized(
                        command(fixture),
                        executions::incrementAndGet
                )
        );

        assertAll(
                () -> assertTrue(
                        exception.getMessage().contains("IR-009")
                ),
                () -> assertEquals(0, executions.get()),
                () -> assertEquals(
                        0, countExecutions(fixture.actionId())
                )
        );
    }
}

