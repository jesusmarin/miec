
package com.miec.action;

import com.miec.authorization.AuthorizationPersistenceService;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationDecision;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Servicio de aplicación para la ejecución autorizada
 * de acciones de MIEC-F03.
 *
 * <p>Implementa IR-009 y reutiliza:
 * <ul>
 *   <li>IR-005: acción y plan pertenecen al mismo ciclo.</li>
 *   <li>IR-008: autorización vinculada a un plan existente.</li>
 *   <li>IR-010: alcance, estado y vigencia.</li>
 * </ul>
 *
 * <p>El servicio verifica la autorización antes de
 * invocar el ejecutor y registra el resultado en
 * la tabla executed_action.
 *
 * <p>Utiliza bloqueos de fila y una transacción para
 * coordinar las operaciones de base de datos.
 *
 * <p>Los efectos externos del ejecutor no pueden
 * revertirse automáticamente mediante esta transacción.
 */
@Service
public class AuthorizedActionExecutionService {

    /** Alcance exigido para ejecutar una acción. */
    private static final String EXECUTE_SCOPE = "EXECUTE_ACTION";

    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationPersistenceService authorizationService;

    /**
     * Contrato para ejecutar una acción previamente autorizada.
     *
     * <p>Las implementaciones deben ser síncronas.
     * Para esta primera versión se recomienda que sus
     * efectos participen en la misma transacción.
     */
    @FunctionalInterface
    public interface ActionExecutor {

        /**
         * Ejecuta la operación concreta.
         *
         * @throws RuntimeException si la ejecución falla
         */
        void execute();
    }

    /**
     * Solicitud de ejecución controlada.
     *
     * @param actionId identificador de la acción
     * @param planId plan que contiene la acción
     * @param cycleId ciclo al que pertenece
     * @param authorizationId autorización que se utilizará
     * @param actionType tipo de acción
     * @param actorId identificador del ejecutor
     */
    public record ExecutionCommand(
            UUID actionId,
            UUID planId,
            UUID cycleId,
            UUID authorizationId,
            String actionType,
            String actorId
    ) {

        /**
         * Valida las referencias obligatorias.
         */
        public ExecutionCommand {
            Objects.requireNonNull(actionId, "actionId es obligatorio");
            Objects.requireNonNull(planId, "planId es obligatorio");
            Objects.requireNonNull(cycleId, "cycleId es obligatorio");
            Objects.requireNonNull(
                    authorizationId,
                    "authorizationId es obligatorio"
            );

            actionType = requireText(actionType, "actionType", 100);
            actorId = requireText(actorId, "actorId", 255);
        }
    }

    /**
     * Resultado de una acción finalizada.
     *
     * @param actionId identificador de la acción
     * @param status estado final registrado
     */
    public record ExecutionResult(
            UUID actionId,
            String status
    ) {
    }

    /**
     * Excepción que identifica un rechazo de autorización
     * o una incompatibilidad con el contexto de ejecución.
     */
    public static class ExecutionDeniedException
            extends RuntimeException {

        /**
         * Construye una denegación justificada.
         *
         * @param message explicación del rechazo
         */
        public ExecutionDeniedException(String message) {
            super(message);
        }
    }

    /**
     * Construye el servicio con sus dependencias.
     *
     * @param jdbcTemplate acceso JDBC obligatorio
     * @param authorizationService servicio IR-010 obligatorio
     */
    public AuthorizedActionExecutionService(
            JdbcTemplate jdbcTemplate,
            AuthorizationPersistenceService authorizationService
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate, "JdbcTemplate es obligatorio"
        );
        this.authorizationService = Objects.requireNonNull(
                authorizationService,
                "AuthorizationPersistenceService es obligatorio"
        );
    }

    /**
     * Valida y normaliza un texto obligatorio.
     *
     * @param value texto recibido
     * @param field nombre del campo
     * @param maxLength longitud máxima
     * @return texto normalizado
     */
    private static String requireText(
            String value,
            String field,
            int maxLength
    ) {
        Objects.requireNonNull(value, field + " es obligatorio");

        String result = value.trim();

        if (result.isEmpty() || result.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " debe contener entre 1 y "
                            + maxLength + " caracteres"
            );
        }

        return result;
    }

    /**
     * Bloquea el plan y recupera su ciclo propietario.
     *
     * @param planId identificador del plan
     * @return ciclo propietario
     * @throws ExecutionDeniedException si el plan no existe
     */
    private UUID lockPlan(UUID planId) {
        List<UUID> cycles = jdbcTemplate.query(
                """
                SELECT cycle_id
                FROM intervention_plan
                WHERE plan_id = ?
                FOR UPDATE
                """,
                (rs, row) ->
                        rs.getObject("cycle_id", UUID.class),
                planId
        );

        if (cycles.isEmpty()) {
            throw new ExecutionDeniedException(
                    "El plan de intervención no existe"
            );
        }

        return cycles.getFirst();
    }

    /**
     * Bloquea la autorización durante la comprobación
     * y ejecución transaccional.
     *
     * <p>Coordina la operación con actualizaciones
     * concurrentes de esa misma fila.
     *
     * @param authorizationId identificador solicitado
     */
    private void lockAuthorization(UUID authorizationId) {
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT authorization_id
                FROM authorizations
                WHERE authorization_id = ?
                FOR UPDATE
                """,
                (rs, row) ->
                        rs.getObject("authorization_id", UUID.class),
                authorizationId
        );

        if (ids.isEmpty()) {
            throw new ExecutionDeniedException(
                    "La autorización no existe"
            );
        }
    }

    /**
     * Ejecuta una acción solamente después de
     * comprobar una autorización válida.
     *
     * <p>Secuencia:
     * <ol>
     *   <li>Bloquea el plan y comprueba su ciclo.</li>
     *   <li>Bloquea la autorización indicada.</li>
     *   <li>Consulta la política IR-010.</li>
     *   <li>Inserta la acción en estado IN_PROGRESS.</li>
     *   <li>Invoca el ejecutor.</li>
     *   <li>Marca la acción como COMPLETED.</li>
     * </ol>
     *
     * <p>El registro inicial impide ejecutar dos veces
     * el mismo actionId mediante esta operación, gracias
     * a la clave primaria de executed_action.
     *
     * <p>Si el ejecutor falla, la transacción revierte
     * sus modificaciones de base de datos. No se asume
     * que pueda deshacer efectos externos.
     *
     * @param command solicitud de ejecución
     * @param executor implementación de la acción
     * @return resultado de la operación
     * @throws ExecutionDeniedException si no está autorizada
     */
    @Transactional
    public ExecutionResult executeAuthorized(
            ExecutionCommand command,
            ActionExecutor executor
    ) {
        Objects.requireNonNull(command, "command es obligatorio");
        Objects.requireNonNull(executor, "executor es obligatorio");

        UUID planCycleId = lockPlan(command.planId());

        if (!planCycleId.equals(command.cycleId())) {
            throw new ExecutionDeniedException(
                    "IR-005: el plan pertenece a otro ciclo"
            );
        }

        lockAuthorization(command.authorizationId());

        AuthorizationUseRequest request =
                new AuthorizationUseRequest(
                        command.planId(),
                        command.actionId(),
                        EXECUTE_SCOPE,
                        Instant.now()
                );

        AuthorizationDecision decision =
                authorizationService.evaluate(
                        command.authorizationId(),
                        request
                );

        if (!decision.allowed()) {
            throw new ExecutionDeniedException(
                    "IR-009: ejecución denegada: "
                            + decision.reason()
            );
        }

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO executed_action (
                    action_id,
                    plan_id,
                    cycle_id,
                    action_type,
                    execution_status,
                    started_at,
                    actor_id,
                    authorization_id
                )
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
                """,
                command.actionId(),
                command.planId(),
                command.cycleId(),
                command.actionType(),
                "IN_PROGRESS",
                command.actorId(),
                command.authorizationId()
        );

        if (inserted != 1) {
            throw new IllegalStateException(
                    "No se pudo registrar el inicio de ejecución"
            );
        }

        executor.execute();

        int updated = jdbcTemplate.update(
                """
                UPDATE executed_action
                SET execution_status = ?,
                    completed_at = CURRENT_TIMESTAMP
                WHERE action_id = ?
                  AND execution_status = ?
                """,
                "COMPLETED",
                command.actionId(),
                "IN_PROGRESS"
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "No se pudo registrar la finalización"
            );
        }

        return new ExecutionResult(
                command.actionId(),
                "COMPLETED"
        );
    }
}

