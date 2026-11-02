
package com.miec.action;

import com.miec.action.AuthorizedActionExecutionService.ExecutionCommand;
import com.miec.action.AuthorizedActionExecutionService.ExecutionDeniedException;
import com.miec.action.ExecutionRetryPolicy.ExecutionStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MIEC-F03 IR-009:
 * Pruebas de integración del contrato de acciones JSON v01.
 *
 * <p>Verifica la integración entre:
 * <ul>
 *   <li>PlannedActionJsonV01.</li>
 *   <li>PlannedActionPolicy.</li>
 *   <li>AuthorizedActionExecutionService.</li>
 *   <li>ExecutionAttemptService.</li>
 *   <li>AuthorizationPersistenceService.</li>
 *   <li>Flyway V003.</li>
 * </ul>
 *
 * <p>Las acciones deben estar declaradas correctamente
 * en el contrato JSON del plan de intervención antes
 * de que el coordinador permita ejecutarlas.
 *
 * <p>Los intentos se registran en execution_attempt.
 * La tabla executed_action pertenece al mecanismo
 * histórico de ejecución.
 *
 * <p>Esta clase NO utiliza @Transactional porque
 * ExecutionAttemptService confirma los registros
 * mediante transacciones REQUIRES_NEW.
 *
 * <p>Los escenarios utilizan UUID independientes
 * para evitar colisiones entre pruebas.
 */
@SpringBootTest(properties = {
        "spring.datasource.url="
                + "jdbc:h2:mem:miec_ir009_json_v01;"
                + "DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DisplayName(
        "MIEC-F03 IR-009 - Integración del contrato JSON v01"
)
class PlannedActionExecutionIntegrationTest {

    /**
     * Acceso a la base de datos de integración.
     */
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Coordinador de ejecución autorizada.
     */
    @Autowired
    private AuthorizedActionExecutionService service;

    /**
     * Identificadores persistidos de un escenario.
     *
     * @param planId identificador del plan
     * @param cycleId identificador del ciclo
     * @param actionId identificador de la acción
     * @param authorizationId autorización de ejecución
     */
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
     * <p>El contenido JSON es proporcionado por
     * cada escenario para verificar su comportamiento.
     *
     * <p>La autorización siempre se crea vigente
     * y aprobada. De esta manera se aíslan los
     * resultados de la política de planificación.
     *
     * @param actionsJson documento JSON del plan
     * @return identificadores del escenario
     */
    private Fixture createFixture(String actionsJson) {

        Objects.requireNonNull(
                actionsJson,
                "actionsJson es obligatorio"
        );

        UUID systemId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();

        // 1. Crear sistema objetivo.
        assertEquals(
                1,
                jdbc.update(
                        """
                        INSERT INTO system_objective (
                            system_id,
                            name
                        )
                        VALUES (?, ?)
                        """,
                        systemId,
                        "IR-009 JSON v01"
                )
        );

        // 2. Crear ciclo asociado.
        assertEquals(
                1,
                jdbc.update(
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

        // 3. Persistir plan y contrato JSON.
        assertEquals(
                1,
                jdbc.update(
                        """
                        INSERT INTO intervention_plan (
                            plan_id,
                            cycle_id,
                            status,
                            actions
                        )
                        VALUES (?, ?, ?, ? FORMAT JSON)
                        """,
                        planId,
                        cycleId,
                        "PROPOSED",
                        actionsJson
                )
        );

        // 4. Crear autorización vigente.
        assertEquals(
                1,
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
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        authorizationId,
                        planId,
                        actionId,
                        "requester",
                        "approver",
                        "EXECUTE_ACTION",
                        "APPROVED",
                        Timestamp.from(
                                Instant.now().minusSeconds(60)
                        ),
                        Timestamp.from(
                                Instant.now().plusSeconds(3600)
                        )
                )
        );

        return new Fixture(
                planId,
                cycleId,
                actionId,
                authorizationId
        );
    }

    /**
     * Construye la solicitud de ejecución
     * correspondiente al escenario.
     *
     * @param fixture referencias persistidas
     * @return comando válido estructuralmente
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
     * Construye un contrato JSON v01 con una acción.
     *
     * @param actionId identidad de la acción
     * @param actionType tipo declarado
     * @return documento JSON v01
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
                """.formatted(
                actionId,
                actionType
        );
    }

    /**
     * Cuenta los intentos registrados para una acción.
     *
     * <p>Consulta execution_attempt, tabla creada
     * por Flyway V003.
     *
     * @param actionId identidad planificada
     * @return cantidad de intentos persistidos
     */
    private int countExecutions(UUID actionId) {

        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM execution_attempt
                WHERE planned_action_id = ?
                """,
                Integer.class,
                actionId
        );

        return Objects.requireNonNull(count);
    }

    /**
     * Recupera el estado del último intento
     * registrado para una acción.
     *
     * @param actionId identidad planificada
     * @return último estado persistido
     */
    private String findLastExecutionStatus(UUID actionId) {

        return jdbc.queryForObject(
                """
                SELECT execution_status
                FROM execution_attempt
                WHERE planned_action_id = ?
                ORDER BY attempt_number DESC
                LIMIT 1
                """,
                String.class,
                actionId
        );
    }

    /**
     * Comprueba la ejecución de una acción
     * declarada en el contrato JSON v01.
     *
     * <p>El coordinador debe:
     * <ul>
     *   <li>Aceptar la acción planificada.</li>
     *   <li>Invocar el ejecutor una sola vez.</li>
     *   <li>Persistir exactamente un intento.</li>
     *   <li>Finalizarlo en COMPLETED.</li>
     * </ul>
     */
    @Test
    @DisplayName(
            "Debe ejecutar una acción existente en el contrato JSON v01"
    )
    void shouldExecuteDeclaredAction() {

        UUID initialActionId = UUID.randomUUID();

        Fixture fixture = createFixture(
                validJson(
                        initialActionId,
                        "PROCESS_EXECUTION"
                )
        );

        // El contrato definitivo debe referenciar
        // la acción autorizada por createFixture().
        assertEquals(
                1,
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
                )
        );

        AtomicInteger executions = new AtomicInteger();

        var result = service.executeAuthorized(
                command(fixture),
                executions::incrementAndGet
        );

        assertAll(
                () -> assertEquals(
                        1,
                        executions.get()
                ),
                () -> assertEquals(
                        fixture.actionId(),
                        result.actionId()
                ),
                () -> assertEquals(
                        "COMPLETED",
                        result.status()
                ),
                () -> assertEquals(
                        1,
                        countExecutions(fixture.actionId())
                ),
                () -> assertEquals(
                        ExecutionStatus.COMPLETED.name(),
                        findLastExecutionStatus(
                                fixture.actionId()
                        )
                )
        );
    }

    /**
     * Comprueba que una acción ausente
     * del contrato JSON no pueda ejecutarse.
     *
     * <p>El ejecutor no debe ser invocado
     * ni debe crearse un intento persistente.
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
     * Comprueba que el tipo solicitado
     * coincida con el declarado en el plan.
     *
     * <p>La acción se encuentra declarada,
     * pero su tipo no coincide con el comando.
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

        // Declarar la identidad correcta,
        // pero con un tipo incompatible.
        assertEquals(
                1,
                jdbc.update(
                        """
                        UPDATE intervention_plan
                        SET actions = ? FORMAT JSON
                        WHERE plan_id = ?
                        """,
                        validJson(
                                fixture.actionId(),
                                "MEASUREMENT"
                        ),
                        fixture.planId()
                )
        );

        assertDeniedWithoutExecution(fixture);
    }

    /**
     * Comprueba que una lista vacía
     * no permita ejecutar acciones.
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
     * Comprueba que un contrato con versión
     * desconocida sea rechazado.
     *
     * <p>Solo se acepta la versión "01".
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
     * Comprueba que el atributo actions
     * tenga la estructura esperada.
     *
     * <p>Un objeto JSON no puede sustituir
     * el arreglo obligatorio de acciones.
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
     * Verifica que una solicitud inválida sea denegada
     * sin llegar a ejecutar la acción.
     *
     * <p>Este método centraliza las comprobaciones:
     * <ul>
     *   <li>La excepción debe identificar IR-009.</li>
     *   <li>El ejecutor no debe ser invocado.</li>
     *   <li>No debe existir ningún intento persistido.</li>
     * </ul>
     *
     * @param fixture escenario inválido
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
                        exception.getMessage().contains("IR-009"),
                        "La excepción debe identificar IR-009"
                ),
                () -> assertEquals(
                        0,
                        executions.get(),
                        "No debe invocarse el ejecutor"
                ),
                () -> assertEquals(
                        0,
                        countExecutions(fixture.actionId()),
                        "No debe persistirse un intento rechazado"
                )
        );
    }
}
