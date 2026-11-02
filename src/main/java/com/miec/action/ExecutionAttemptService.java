
package com.miec.action;

import com.miec.action.ExecutionRetryPolicy.EffectOutcome;
import com.miec.action.ExecutionRetryPolicy.ExecutionStatus;
import com.miec.action.ExecutionRetryPolicy.RetryRequest;
import com.miec.action.PlannedActionPolicy.ActionRequest;
import com.miec.action.PlannedActionPolicy.PlanSnapshot;
import com.miec.authorization.AuthorizationPersistenceService;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MIEC-F03 IR-009: persistencia y coordinación de intentos.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Validar la existencia de la acción planificada.</li>
 *   <li>Revalidar la autorización de cada nuevo intento.</li>
 *   <li>Asignar números de intento bajo bloqueo del plan.</li>
 *   <li>Aplicar la política de reintentos seguros.</li>
 *   <li>Registrar transiciones de ejecución.</li>
 *   <li>Conservar el historial independientemente de
 *       la transacción del ejecutor.</li>
 * </ul>
 *
 * <p>Utiliza transacciones REQUIRES_NEW para confirmar
 * los registros de auditoría por separado.
 *
 * <p>Los métodos de finalización deben ser invocados
 * únicamente por un coordinador de ejecución confiable.
 *
 * <p>Este servicio no invoca ejecutores externos.
 */
@Service
public class ExecutionAttemptService {

    /** Alcance necesario para ejecutar acciones. */
    private static final String EXECUTE_SCOPE = "EXECUTE_ACTION";

    /** Límite fijo de intentos de la versión 01. */
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PlannedActionPolicy plannedPolicy;
    private final PlannedActionJsonV01 plannedJson;
    private final AuthorizationPersistenceService authorizations;
    private final ExecutionRetryPolicy retryPolicy;

    /**
     * Representación de lectura de un intento persistido.
     *
     * <p>Los valores son independientes del contexto JPA
     * y reflejan el estado almacenado en la base de datos.
     */
    public record AttemptSnapshot(
            UUID executionId,
            UUID plannedActionId,
            UUID planId,
            UUID cycleId,
            UUID authorizationId,
            int attemptNumber,
            ExecutionStatus status,
            EffectOutcome effectOutcome,
            String actorId,
            Instant startedAt,
            Instant completedAt,
            String resultReference,
            String errorReference,
            long version
    ) {
    }

    /**
     * Solicitud para crear un intento de una acción.
     *
     * @param plannedActionId acción planificada
     * @param planId plan propietario
     * @param cycleId ciclo propietario
     * @param authorizationId autorización utilizada
     * @param actionType tipo definido en el plan
     * @param actorId identificador del ejecutor
     */
    public record CreateAttemptCommand(
            UUID plannedActionId,
            UUID planId,
            UUID cycleId,
            UUID authorizationId,
            String actionType,
            String actorId
    ) {
        public CreateAttemptCommand {
            Objects.requireNonNull(
                    plannedActionId, "plannedActionId es obligatorio"
            );
            Objects.requireNonNull(planId, "planId es obligatorio");
            Objects.requireNonNull(cycleId, "cycleId es obligatorio");
            Objects.requireNonNull(
                    authorizationId, "authorizationId es obligatorio"
            );

            actionType = requireText(
                    actionType, "actionType", 100
            );
            actorId = requireText(actorId, "actorId", 255);
        }
    }

    /**
     * Excepción ante una operación de ejecución no admitida.
     */
    public static class AttemptDeniedException
            extends RuntimeException {

        public AttemptDeniedException(String message) {
            super(message);
        }
    }

    /**
     * Configura el servicio y sus transacciones independientes.
     *
     * @param jdbc acceso JDBC
     * @param manager gestor de transacciones
     * @param plannedPolicy política de pertenencia al plan
     * @param plannedJson intérprete JSON v01
     * @param authorizations servicio IR-010
     * @param retryPolicy política de reintentos
     */
    public ExecutionAttemptService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            PlannedActionPolicy plannedPolicy,
            PlannedActionJsonV01 plannedJson,
            AuthorizationPersistenceService authorizations,
            ExecutionRetryPolicy retryPolicy
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.plannedPolicy = Objects.requireNonNull(plannedPolicy);
        this.plannedJson = Objects.requireNonNull(plannedJson);
        this.authorizations = Objects.requireNonNull(authorizations);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);

        Objects.requireNonNull(manager);

        this.transaction = new TransactionTemplate(manager);

        this.transaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    /**
     * Valida un texto obligatorio.
     */
    private static String requireText(
            String value,
            String field,
            int maximum
    ) {
        Objects.requireNonNull(
                value, field + " es obligatorio"
        );

        String normalized = value.trim();

        if (normalized.isEmpty()
                || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " debe contener entre 1 y "
                            + maximum + " caracteres"
            );
        }

        return normalized;
    }

    /**
     * Convierte una fecha SQL a Instant.
     */
    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Reconstruye un intento a partir de JDBC.
     */
    private static AttemptSnapshot mapAttempt(
            ResultSet rs,
            int row
    ) throws SQLException {
        return new AttemptSnapshot(
                rs.getObject("execution_id", UUID.class),
                rs.getObject("planned_action_id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getObject("cycle_id", UUID.class),
                rs.getObject("authorization_id", UUID.class),
                rs.getInt("attempt_number"),
                ExecutionStatus.valueOf(
                        rs.getString("execution_status")
                ),
                EffectOutcome.valueOf(
                        rs.getString("effect_outcome")
                ),
                rs.getString("actor_id"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")),
                rs.getString("result_reference"),
                rs.getString("error_reference"),
                rs.getLong("version")
        );
    }

    /**
     * Columnas utilizadas para reconstruir un intento.
     */
    private static final String ATTEMPT_COLUMNS = """
            execution_id,
            planned_action_id,
            plan_id,
            cycle_id,
            authorization_id,
            attempt_number,
            execution_status,
            effect_outcome,
            actor_id,
            started_at,
            completed_at,
            result_reference,
            error_reference,
            version
            """;

    /**
     * Consulta el intento de mayor numeración.
     *
     * <p>Durante la creación, el llamador debe haber
     * bloqueado previamente el plan correspondiente.
     */
    private AttemptSnapshot findLatest(
            UUID planId,
            UUID plannedActionId
    ) {
        List<AttemptSnapshot> results = jdbc.query(
                """
                SELECT %s
                FROM execution_attempt
                WHERE plan_id = ?
                  AND planned_action_id = ?
                ORDER BY attempt_number DESC
                LIMIT 1
                """.formatted(ATTEMPT_COLUMNS),
                ExecutionAttemptService::mapAttempt,
                planId,
                plannedActionId
        );

        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * Bloquea un intento antes de modificar su estado.
     */
    private AttemptSnapshot lockAttempt(UUID executionId) {
        List<AttemptSnapshot> results = jdbc.query(
                """
                SELECT %s
                FROM execution_attempt
                WHERE execution_id = ?
                FOR UPDATE
                """.formatted(ATTEMPT_COLUMNS),
                ExecutionAttemptService::mapAttempt,
                executionId
        );

        if (results.isEmpty()) {
            throw new AttemptDeniedException(
                    "IR-009: intento de ejecución inexistente"
            );
        }

        return results.getFirst();
    }

    /**
     * Bloquea el plan para coordinar la numeración
     * de intentos de una misma acción.
     *
     * <p>Este bloqueo también se utiliza para impedir
     * que dos transacciones creen simultáneamente
     * el primer intento de una acción.
     */
    private PlanSnapshot lockAndReadPlan(UUID planId) {
        List<PlanSnapshot> plans = jdbc.query(
                """
                SELECT plan_id, cycle_id, actions
                FROM intervention_plan
                WHERE plan_id = ?
                FOR UPDATE
                """,
                (rs, row) -> plannedJson.parse(
                        rs.getObject("plan_id", UUID.class),
                        rs.getObject("cycle_id", UUID.class),
                        rs.getString("actions")
                ),
                planId
        );

        if (plans.isEmpty()) {
            throw new AttemptDeniedException(
                    "IR-009: plan de intervención inexistente"
            );
        }

        return plans.getFirst();
    }

    /**
     * Bloquea la autorización durante su evaluación.
     */
    private void lockAuthorization(UUID authorizationId) {
        List<UUID> ids = jdbc.query(
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
            throw new AttemptDeniedException(
                    "IR-010: autorización inexistente"
            );
        }
    }

    /**
     * Comprueba la autorización vigente para esta acción.
     *
     * @return true si la política IR-010 permite utilizarla
     */
    private boolean isAuthorized(CreateAttemptCommand command) {
        var request = new AuthorizationUseRequest(
                command.planId(),
                command.plannedActionId(),
                EXECUTE_SCOPE,
                Instant.now()
        );

        return authorizations.evaluate(
                command.authorizationId(),
                request
        ).allowed();
    }

    /**
     * Crea un intento pendiente en una transacción propia.
     *
     * <p>El plan se bloquea antes de consultar el historial.
     * Así se serializa la asignación de attemptNumber.
     *
     * <p>Para un reintento se exige:
     * <ul>
     *   <li>Último estado FAILED.</li>
     *   <li>Ausencia de efectos confirmada.</li>
     *   <li>Autorización nuevamente válida.</li>
     *   <li>No superar MAX_ATTEMPTS.</li>
     * </ul>
     *
     * @param command solicitud validada estructuralmente
     * @return nuevo intento pendiente
     */
    public AttemptSnapshot createAttempt(
            CreateAttemptCommand command
    ) {
        Objects.requireNonNull(
                command, "command es obligatorio"
        );

        return Objects.requireNonNull(
                transaction.execute(status -> {

                    final PlanSnapshot plan;

                    try {
                        plan = lockAndReadPlan(command.planId());
                        Integer legacyCount = jdbc.queryForObject(
                                """
                                SELECT COUNT(*) FROM executed_action
                                WHERE action_id = ?
                                """,
                                Integer.class,
                                command.plannedActionId()
                        );
                        if (legacyCount != null && legacyCount > 0) {
                            throw new AttemptDeniedException(
                                    "IR-009: la acción tiene una ejecución histórica en executed_action"
                            );
                        }
                    } catch (IllegalArgumentException exception) {
                        throw new AttemptDeniedException(
                                "IR-009: contrato JSON inválido: "
                                        + exception.getMessage()
                        );
                    }

                    var plannedDecision = plannedPolicy.evaluate(
                            plan,
                            new ActionRequest(
                                    command.planId(),
                                    command.cycleId(),
                                    command.plannedActionId(),
                                    command.actionType()
                            )
                    );

                    if (!plannedDecision.allowed()) {
                        throw new AttemptDeniedException(
                                "IR-009: acción no planificada: "
                                        + plannedDecision.reason()
                        );
                    }

                    lockAuthorization(command.authorizationId());

                    boolean authorized = isAuthorized(command);

                    if (!authorized) {
                        throw new AttemptDeniedException(
                                "IR-010: autorización inválida"
                        );
                    }

                    AttemptSnapshot latest = findLatest(
                            command.planId(),
                            command.plannedActionId()
                    );

                    int nextNumber = 1;

                    if (latest != null) {
                        var decision = retryPolicy.evaluate(
                                new RetryRequest(
                                        latest.status(),
                                        latest.effectOutcome(),
                                        latest.attemptNumber(),
                                        MAX_ATTEMPTS,
                                        authorized
                                )
                        );

                        if (!decision.allowed()) {
                            throw new AttemptDeniedException(
                                    decision.reason()
                            );
                        }

                        nextNumber = decision.nextAttemptNumber();
                    }

                    UUID executionId = UUID.randomUUID();

                    int inserted = jdbc.update(
                            """
                            INSERT INTO execution_attempt (
                                execution_id,
                                planned_action_id,
                                plan_id,
                                cycle_id,
                                authorization_id,
                                attempt_number,
                                execution_status,
                                effect_outcome,
                                actor_id,
                                version
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                            """,
                            executionId,
                            command.plannedActionId(),
                            command.planId(),
                            command.cycleId(),
                            command.authorizationId(),
                            nextNumber,
                            ExecutionStatus.PENDING.name(),
                            EffectOutcome.EFFECT_UNKNOWN.name(),
                            command.actorId()
                    );

                    if (inserted != 1) {
                        throw new IllegalStateException(
                                "IR-009: no se pudo crear el intento"
                        );
                    }

                    return lockAttempt(executionId);
                })
        );
    }

    /**
     * Inicia un intento pendiente y confirma su estado
     * antes de que el coordinador produzca efectos.
     *
     * @param executionId identidad del intento
     * @return intento actualizado
     */
    public AttemptSnapshot startAttempt(UUID executionId) {
        Objects.requireNonNull(executionId);

        return Objects.requireNonNull(
                transaction.execute(status -> {
                    AttemptSnapshot current = lockAttempt(executionId);

                    if (current.status() != ExecutionStatus.PENDING) {
                        throw new AttemptDeniedException(
                                "IR-009: solamente PENDING puede iniciarse"
                        );
                    }

                    int updated = jdbc.update(
                            """
                            UPDATE execution_attempt
                            SET execution_status = ?,
                                started_at = CURRENT_TIMESTAMP,
                                version = version + 1
                            WHERE execution_id = ?
                              AND execution_status = ?
                              AND version = ?
                            """,
                            ExecutionStatus.IN_PROGRESS.name(),
                            executionId,
                            ExecutionStatus.PENDING.name(),
                            current.version()
                    );

                    requireUpdated(updated);

                    return lockAttempt(executionId);
                })
        );
    }

    /**
     * Registra una ejecución finalizada satisfactoriamente.
     *
     * <p>Esta operación confirma el resultado en su propia
     * transacción de auditoría.
     */
    public AttemptSnapshot completeAttempt(
            UUID executionId,
            String resultReference
    ) {
        return finish(
                executionId,
                ExecutionStatus.COMPLETED,
                EffectOutcome.EFFECT_APPLIED,
                resultReference,
                null
        );
    }

    /**
     * Registra un fallo con resultado de efectos conocido.
     *
     * <p>NO_EFFECT_CONFIRMED solo puede declararlo
     * un coordinador confiable después de verificar
     * efectivamente la ausencia de efectos.
     *
     * <p>Si existe incertidumbre, utilizar markUnknown().
     */
    public AttemptSnapshot failAttempt(
            UUID executionId,
            EffectOutcome outcome,
            String errorReference
    ) {
        Objects.requireNonNull(outcome, "outcome es obligatorio");

        if (outcome == EffectOutcome.EFFECT_UNKNOWN) {
            throw new IllegalArgumentException(
                    "Usar markUnknown para resultados inciertos"
            );
        }

        return finish(
                executionId,
                ExecutionStatus.FAILED,
                outcome,
                null,
                requireText(errorReference, "errorReference", 500)
        );
    }

    /**
     * Registra un resultado incierto que no permite
     * reintentos automáticos.
     */
    public AttemptSnapshot markUnknown(
            UUID executionId,
            String errorReference
    ) {
        return finish(
                executionId,
                ExecutionStatus.UNKNOWN,
                EffectOutcome.EFFECT_UNKNOWN,
                null,
                requireText(errorReference, "errorReference", 500)
        );
    }

    /**
     * Registra uno de los estados terminales.
     *
     * <p>La transición solo se admite desde IN_PROGRESS.
     */
    private AttemptSnapshot finish(
            UUID executionId,
            ExecutionStatus next,
            EffectOutcome outcome,
            String resultReference,
            String errorReference
    ) {
        Objects.requireNonNull(
                executionId, "executionId es obligatorio"
        );

        if (resultReference != null) {
            resultReference = requireText(
                    resultReference, "resultReference", 500
            );
        }

        final String result = resultReference;

        return Objects.requireNonNull(
                transaction.execute(status -> {
                    AttemptSnapshot current = lockAttempt(executionId);

                    if (current.status()
                            != ExecutionStatus.IN_PROGRESS) {
                        throw new AttemptDeniedException(
                                "IR-009: el intento no está en ejecución"
                        );
                    }

                    int updated = jdbc.update(
                            """
                            UPDATE execution_attempt
                            SET execution_status = ?,
                                effect_outcome = ?,
                                completed_at = CURRENT_TIMESTAMP,
                                result_reference = ?,
                                error_reference = ?,
                                version = version + 1
                            WHERE execution_id = ?
                              AND execution_status = ?
                              AND version = ?
                            """,
                            next.name(),
                            outcome.name(),
                            result,
                            errorReference,
                            executionId,
                            ExecutionStatus.IN_PROGRESS.name(),
                            current.version()
                    );

                    requireUpdated(updated);

                    return lockAttempt(executionId);
                })
        );
    }

    /**
     * Comprueba que una transición haya actualizado
     * exactamente un registro.
     */
    private static void requireUpdated(int rows) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "IR-009: actualización concurrente o inválida"
            );
        }
    }

    /**
     * Consulta el historial ordenado por número de intento.
     *
     * @param planId plan propietario
     * @param plannedActionId acción planificada
     * @return historial ordenado e inmutable
     */
    public List<AttemptSnapshot> findHistory(
            UUID planId,
            UUID plannedActionId
    ) {
        Objects.requireNonNull(planId);
        Objects.requireNonNull(plannedActionId);

        return List.copyOf(jdbc.query(
                """
                SELECT %s
                FROM execution_attempt
                WHERE plan_id = ?
                  AND planned_action_id = ?
                ORDER BY attempt_number ASC
                """.formatted(ATTEMPT_COLUMNS),
                ExecutionAttemptService::mapAttempt,
                planId,
                plannedActionId
        ));
    }

    /**
     * Inicia un intento con revalidación de plan y autorización.
     * El bloqueo del plan precede al del intento y autorización.
     * La aprobación no es una garantía frente a revocaciones posteriores
     * al commit o frente a sistemas externos que no verifican permisos.
     */
    public AttemptSnapshot startAuthorizedAttempt(UUID executionId, String actionType) {
        Objects.requireNonNull(executionId, "executionId es obligatorio");
        String type = requireText(actionType, "actionType", 100);
        return Objects.requireNonNull(transaction.execute(tx -> {
            List<UUID> planIds = jdbc.query(
                    "SELECT plan_id FROM execution_attempt WHERE execution_id = ?",
                    (rs, row) -> rs.getObject("plan_id", UUID.class),
                    executionId
            );
            if (planIds.isEmpty()) {
                throw new AttemptDeniedException("IR-009: intento inexistente");
            }
            PlanSnapshot plan = lockAndReadPlan(planIds.getFirst());
            AttemptSnapshot current = lockAttempt(executionId);
            if (current.status() != ExecutionStatus.PENDING) {
                throw new AttemptDeniedException("IR-009: solo PENDING puede iniciarse");
            }
            var plannedDecision = plannedPolicy.evaluate(
                    plan,
                    new ActionRequest(
                            current.planId(), current.cycleId(),
                            current.plannedActionId(), type
                    )
            );
            if (!plannedDecision.allowed()) {
                throw new AttemptDeniedException(
                        "IR-009: acción no planificada: " + plannedDecision.reason()
                );
            }
            lockAuthorization(current.authorizationId());
            var authorized = authorizations.evaluate(
                    current.authorizationId(),
                    new AuthorizationUseRequest(
                            current.planId(),
                            current.plannedActionId(),
                            EXECUTE_SCOPE,
                            Instant.now()
                    )
            );
            if (!authorized.allowed()) {
                throw new AttemptDeniedException(
                        "IR-010: autorización inválida: " + authorized.reason()
                );
            }
            requireUpdated(jdbc.update(
                    """
                    UPDATE execution_attempt
                    SET execution_status = 'IN_PROGRESS',
                        started_at = CURRENT_TIMESTAMP,
                        version = version + 1
                    WHERE execution_id = ? AND execution_status = 'PENDING'
                      AND version = ?
                    """,
                    executionId, current.version()
            ));
            return lockAttempt(executionId);
        }));
    }
    /**
     * Registra rechazo anterior al efecto externo. Nunca se invoca
     * después de que el ejecutor haya comenzado.
     */
    public AttemptSnapshot rejectPendingBeforeExecution(
            UUID executionId, String errorReference
    ) {
        Objects.requireNonNull(executionId, "executionId es obligatorio");
        String reference = requireText(errorReference, "errorReference", 500);
        return Objects.requireNonNull(transaction.execute(tx -> {
            AttemptSnapshot current = lockAttempt(executionId);
            if (current.status() != ExecutionStatus.PENDING) {
                throw new AttemptDeniedException(
                        "IR-009: solamente PENDING puede rechazarse antes de ejecutar"
                );
            }
            requireUpdated(jdbc.update(
                    """
                    UPDATE execution_attempt
                    SET execution_status = 'FAILED',
                        effect_outcome = 'NO_EFFECT_CONFIRMED',
                        started_at = CURRENT_TIMESTAMP,
                        completed_at = CURRENT_TIMESTAMP,
                        error_reference = ?,
                        version = version + 1
                    WHERE execution_id = ? AND execution_status = 'PENDING'
                      AND version = ?
                    """,
                    reference, executionId, current.version()
            ));
            return lockAttempt(executionId);
        }));
    }
}

