
package com.miec.action;

import com.miec.action.AuthorizedActionExecutionService.ActionExecutor;
import com.miec.action.AuthorizedActionExecutionService.ExecutionCommand;
import com.miec.action.AuthorizedActionExecutionService.ExecutionDeniedException;
import com.miec.action.AuthorizedActionExecutionService.ExecutionResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración del control de ejecución IR-009.
 *
 * <p>Verifica que ninguna operación alcance al ejecutor
 * cuando su autorización es inexistente, inválida,
 * vencida, revocada o ajena al contexto solicitado.
 *
 * <p>Comprueba también:
 * <ul>
 *   <li>La ejecución autorizada.</li>
 *   <li>La persistencia del resultado.</li>
 *   <li>La trazabilidad hacia la autorización.</li>
 *   <li>La prevención de ejecuciones duplicadas.</li>
 * </ul>
 *
 * <p>Estas pruebas no invocan servicios externos.
 * El ejecutor utiliza un contador en memoria.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir009_test;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-009 - Autorización previa a la ejecución"
)
class AuthorizedActionExecutionServiceTest {

    /** Servicio de aplicación sometido a prueba. */
    @Autowired
    private AuthorizedActionExecutionService service;

    /** Acceso JDBC a la base de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Referencias necesarias para una ejecución.
     *
     * @param planId identificador del plan
     * @param cycleId identificador del ciclo
     * @param actionId identificador de la acción
     */
    private record Fixture(
            UUID planId,
            UUID cycleId,
            UUID actionId
    ) {
    }

    /**
     * Crea un sistema, ciclo y plan válidos.
     *
     * @return referencias del escenario de ejecución
     */
    private Fixture createFixture() {
        UUID systemId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        assertEquals(1, jdbcTemplate.update(
                """
                INSERT INTO system_objective (
                    system_id, name
                )
                VALUES (?, ?)
                """,
                systemId,
                "Sistema de integración IR-009"
        ));

        assertEquals(1, jdbcTemplate.update(
                """
                INSERT INTO cycle (
                    cycle_id, system_id
                )
                VALUES (?, ?)
                """,
                cycleId,
                systemId
        ));

        assertEquals(1, jdbcTemplate.update(
                """
                INSERT INTO intervention_plan (
                    plan_id, cycle_id, status
                )
                VALUES (?, ?, ?)
                """,
                planId,
                cycleId,
                "PROPOSED"
        ));

        return new Fixture(planId, cycleId, actionId);
    }

    /**
     * Inserta una autorización con referencias
     * explícitas al plan y a la acción.
     *
     * @param fixture contexto de la operación
     * @param status estado de autorización
     * @param scope alcance declarado
     * @param expiresAt vencimiento opcional
     * @param revokedAt revocación opcional
     * @return identificador de autorización
     */
    private UUID createAuthorization(
            Fixture fixture,
            String status,
            String scope,
            Instant expiresAt,
            Instant revokedAt
    ) {
        UUID authorizationId = UUID.randomUUID();

        assertEquals(1, jdbcTemplate.update(
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
                    expires_at,
                    revoked_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                authorizationId,
                fixture.planId(),
                fixture.actionId(),
                "miec-requester",
                "APPROVED".equals(status)
                        ? "human-approver"
                        : null,
                scope,
                status,
                Timestamp.from(Instant.now().minusSeconds(3600)),
                toTimestamp(expiresAt),
                toTimestamp(revokedAt)
        ));

        return authorizationId;
    }

    /**
     * Convierte un instante opcional a TIMESTAMP.
     *
     * @param value instante o null
     * @return representación JDBC
     */
    private Timestamp toTimestamp(Instant value) {
        return value == null
                ? null
                : Timestamp.from(value);
    }

    /**
     * Crea una autorización válida durante
     * el período actual de ejecución.
     *
     * @param fixture contexto de ejecución
     * @return autorización válida
     */
    private UUID createValidAuthorization(Fixture fixture) {
        return createAuthorization(
                fixture,
                "APPROVED",
                "EXECUTE_ACTION",
                Instant.now().plusSeconds(3600),
                null
        );
    }

    /**
     * Construye la solicitud de ejecución.
     *
     * @param fixture referencias del escenario
     * @param authorizationId autorización a utilizar
     * @return comando válido
     */
    private ExecutionCommand command(
            Fixture fixture,
            UUID authorizationId
    ) {
        return new ExecutionCommand(
                fixture.actionId(),
                fixture.planId(),
                fixture.cycleId(),
                authorizationId,
                "PROCESS_EXECUTION",
                "miec-actor"
        );
    }

    /**
     * Cuenta los registros de ejecución de una acción.
     *
     * @param actionId identificador consultado
     * @return cantidad de registros
     */
    private int countActions(UUID actionId) {
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
     * Comprueba la ejecución de una acción
     * con autorización válida.
     *
     * <p>El servicio debe invocar al ejecutor
     * exactamente una vez y registrar la ejecución.
     */
    @Test
    @DisplayName(
            "Debe ejecutar y registrar una acción previamente autorizada"
    )
    void shouldExecuteAuthorizedAction() {
        Fixture fixture = createFixture();
        UUID authorizationId = createValidAuthorization(fixture);

        AtomicInteger executions = new AtomicInteger();

        ExecutionResult result = service.executeAuthorized(
                command(fixture, authorizationId),
                executions::incrementAndGet
        );

        assertAll(
                () -> assertEquals(1, executions.get()),
                () -> assertEquals(
                        fixture.actionId(),
                        result.actionId()
                ),
                () -> assertEquals("COMPLETED", result.status()),
                () -> assertEquals(
                        1,
                        countActions(fixture.actionId())
                )
        );

        UUID persistedAuthorizationId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT authorization_id
                        FROM executed_action
                        WHERE action_id = ?
                        """,
                        UUID.class,
                        fixture.actionId()
                );

        assertEquals(
                authorizationId,
                persistedAuthorizationId
        );
    }

    /**
     * Verifica que una autorización inexistente
     * impida llamar al ejecutor.
     */
    @Test
    @DisplayName(
            "Debe impedir ejecutar una acción sin autorización existente"
    )
    void shouldRejectMissingAuthorization() {
        Fixture fixture = createFixture();
        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> service.executeAuthorized(
                        command(fixture, UUID.randomUUID()),
                        executions::incrementAndGet
                )
        );

        assertEquals(0, executions.get());
        assertEquals(0, countActions(fixture.actionId()));
    }

    /**
     * Comprueba que una autorización pendiente
     * no permita comenzar la ejecución.
     */
    @Test
    @DisplayName(
            "Debe impedir ejecutar una acción con autorización pendiente"
    )
    void shouldRejectPendingAuthorization() {
        Fixture fixture = createFixture();

        UUID authorizationId = createAuthorization(
                fixture,
                "PENDING",
                "EXECUTE_ACTION",
                Instant.now().plusSeconds(3600),
                null
        );

        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> service.executeAuthorized(
                        command(fixture, authorizationId),
                        executions::incrementAndGet
                )
        );

        assertEquals(0, executions.get());
        assertEquals(0, countActions(fixture.actionId()));
    }

    /**
     * Comprueba la denegación de una acción
     * que no coincide con el alcance autorizado.
     */
    @Test
    @DisplayName(
            "Debe impedir ejecutar una acción fuera del alcance autorizado"
    )
    void shouldRejectIncorrectScope() {
        Fixture fixture = createFixture();

        UUID authorizationId = createAuthorization(
                fixture,
                "APPROVED",
                "READ_ONLY",
                Instant.now().plusSeconds(3600),
                null
        );

        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> service.executeAuthorized(
                        command(fixture, authorizationId),
                        executions::incrementAndGet
                )
        );

        assertEquals(0, executions.get());
        assertEquals(0, countActions(fixture.actionId()));
    }

    /**
     * Verifica que el vencimiento se compruebe
     * inmediatamente antes de ejecutar.
     */
    @Test
    @DisplayName(
            "Debe impedir ejecutar con una autorización vencida"
    )
    void shouldRejectExpiredAuthorization() {
        Fixture fixture = createFixture();

        UUID authorizationId = createAuthorization(
                fixture,
                "APPROVED",
                "EXECUTE_ACTION",
                Instant.now().minusSeconds(60),
                null
        );

        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> service.executeAuthorized(
                        command(fixture, authorizationId),
                        executions::incrementAndGet
                )
        );

        assertEquals(0, executions.get());
        assertEquals(0, countActions(fixture.actionId()));
    }

    /**
     * Comprueba que una revocación impida
     * utilizar una autorización aprobada.
     */
    @Test
    @DisplayName(
            "Debe impedir ejecutar con una autorización revocada"
    )
    void shouldRejectRevokedAuthorization() {
        Fixture fixture = createFixture();

        UUID authorizationId = createAuthorization(
                fixture,
                "APPROVED",
                "EXECUTE_ACTION",
                Instant.now().plusSeconds(3600),
                Instant.now().minusSeconds(60)
        );

        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> service.executeAuthorized(
                        command(fixture, authorizationId),
                        executions::incrementAndGet
                )
        );

        assertEquals(0, executions.get());
        assertEquals(0, countActions(fixture.actionId()));
    }

    /**
     * Verifica que la autorización no pueda
     * reutilizarse para otra identidad de acción.
     */
    @Test
    @DisplayName(
            "Debe impedir ejecutar una acción diferente de la autorizada"
    )
    void shouldRejectDifferentAction() {
        Fixture fixture = createFixture();
        UUID authorizationId = createValidAuthorization(fixture);

        ExecutionCommand differentAction = new ExecutionCommand(
                UUID.randomUUID(),
                fixture.planId(),
                fixture.cycleId(),
                authorizationId,
                "PROCESS_EXECUTION",
                "miec-actor"
        );

        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> service.executeAuthorized(
                        differentAction,
                        executions::incrementAndGet
                )
        );

        assertEquals(0, executions.get());
        assertEquals(0, countActions(differentAction.actionId()));
    }

    /**
     * Comprueba que el servicio no permita
     * atribuir un plan a un ciclo diferente.
     */
    @Test
    @DisplayName(
            "Debe impedir ejecutar cuando el plan pertenece a otro ciclo"
    )
    void shouldRejectDifferentCycle() {
        Fixture fixture = createFixture();
        UUID authorizationId = createValidAuthorization(fixture);

        ExecutionCommand wrongCycle = new ExecutionCommand(
                fixture.actionId(),
                fixture.planId(),
                UUID.randomUUID(),
                authorizationId,
                "PROCESS_EXECUTION",
                "miec-actor"
        );

        AtomicInteger executions = new AtomicInteger();

        assertThrows(
                ExecutionDeniedException.class,
                () -> service.executeAuthorized(
                        wrongCycle,
                        executions::incrementAndGet
                )
        );

        assertEquals(0, executions.get());
        assertEquals(0, countActions(fixture.actionId()));
    }

    /**
     * Comprueba que la clave primaria de
     * executed_action impida comenzar una
     * segunda ejecución con el mismo actionId.
     *
     * <p>La nueva inserción debe fallar antes
     * de que se invoque nuevamente al ejecutor.
     */
    @Test
    @DisplayName(
            "Debe impedir ejecutar dos veces la misma identidad de acción"
    )
    void shouldRejectDuplicateActionExecution() {
        Fixture fixture = createFixture();
        UUID authorizationId = createValidAuthorization(fixture);

        AtomicInteger executions = new AtomicInteger();

        service.executeAuthorized(
                command(fixture, authorizationId),
                executions::incrementAndGet
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> service.executeAuthorized(
                        command(fixture, authorizationId),
                        executions::incrementAndGet
                )
        );

        assertEquals(1, executions.get());
        assertEquals(1, countActions(fixture.actionId()));
    }
}
