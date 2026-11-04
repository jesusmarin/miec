
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
    private final ExecutionActivityService activity;
    private final ExecutionReconciliationService reconciliations;

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
            long version,
            String executorId,
            Long fencingToken,
            Instant heartbeatAt,
            Instant leaseExpiresAt
    ) {
        /** Devuelve las credenciales persistidas; un registro histórico carece de ellas. */
        public ExecutionActivityService.Ownership ownership() {
            if (executorId == null || fencingToken == null) {
                throw new AttemptDeniedException("IR-009: intento sin propiedad administrada");
            }
            return new ExecutionActivityService.Ownership(executionId, executorId, fencingToken);
        }
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
     * @param activity identidad, reloj y vigencia de la propiedad
     * @param reconciliations decisiones verificadas para la ruta de reintento protegido
     */
    public ExecutionAttemptService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            PlannedActionPolicy plannedPolicy,
            PlannedActionJsonV01 plannedJson,
            AuthorizationPersistenceService authorizations,
            ExecutionRetryPolicy retryPolicy,
            ExecutionActivityService activity,
            ExecutionReconciliationService reconciliations
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.plannedPolicy = Objects.requireNonNull(plannedPolicy);
        this.plannedJson = Objects.requireNonNull(plannedJson);
        this.authorizations = Objects.requireNonNull(authorizations);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.activity = Objects.requireNonNull(activity);
        this.reconciliations = Objects.requireNonNull(reconciliations);

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
                rs.getLong("version"),
                rs.getString("executor_id"),
                rs.getObject("fencing_token", Long.class),
                toInstant(rs.getTimestamp("heartbeat_at")),
                toInstant(rs.getTimestamp("lease_expires_at"))
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
            version, executor_id, fencing_token, heartbeat_at, lease_expires_at
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
                activity.databaseNow()
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
        return createAttemptInternal(command, null);
    }

    /** Vincula la clave idempotente registrada antes de producir cualquier efecto. */
    AttemptSnapshot createProtectedAttempt(CreateAttemptCommand command, UUID operationKey) {
        return createAttemptInternal(command, Objects.requireNonNull(operationKey));
    }

    /** Crea un intento bajo bloqueo del plan y comprueba su resolución de reconciliación. */
    private AttemptSnapshot createAttemptInternal(CreateAttemptCommand command, UUID operationKey) {
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

                    var registered = jdbc.query(
                            "SELECT operation_key FROM execution_operation WHERE plan_id=? AND planned_action_id=?",
                            (rs,row)->rs.getObject(1,UUID.class),command.planId(),command.plannedActionId());
                    if ((operationKey != null && (registered.isEmpty() || !operationKey.equals(registered.getFirst())))
                            || (operationKey == null && !registered.isEmpty())) {
                        throw new AttemptDeniedException("IR-009: operation requires the protected idempotent route");
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
                        ExecutionStatus retryStatus = latest.status();
                        EffectOutcome retryOutcome = latest.effectOutcome();
                        if(latest.status()==ExecutionStatus.PENDING && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM execution_pending_review WHERE execution_id=?",Integer.class,latest.executionId())==1) {
                            retryStatus=ExecutionStatus.FAILED;
                            retryOutcome=EffectOutcome.NO_EFFECT_CONFIRMED;
                        }
                        if (operationKey != null && (latest.status() == ExecutionStatus.UNKNOWN
                                || latest.status() == ExecutionStatus.IN_PROGRESS)) {
                            UUID previousKey = jdbc.queryForObject(
                                    "SELECT operation_key FROM execution_attempt WHERE execution_id=?",
                                    UUID.class, latest.executionId());
                            if (operationKey.equals(previousKey) && reconciliations.permitsProtectedRetry(latest.executionId())) {
                                retryStatus = ExecutionStatus.FAILED;
                                retryOutcome = EffectOutcome.NO_EFFECT_CONFIRMED;
                            }
                        }
                        var decision = retryPolicy.evaluate(
                                new RetryRequest(
                                        retryStatus,
                                        retryOutcome,
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
                                version, operation_key
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                            """,
                            executionId,
                            command.plannedActionId(),
                            command.planId(),
                            command.cycleId(),
                            command.authorizationId(),
                            nextNumber,
                            ExecutionStatus.PENDING.name(),
                            EffectOutcome.EFFECT_UNKNOWN.name(),
                            command.actorId(), operationKey
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
     * Registra una ejecución finalizada satisfactoriamente.
     *
     * <p>Esta operación confirma el resultado en su propia
     * transacción de auditoría.
     *
     * @param owner credenciales de la adquisición vigente
     * @param resultReference referencia opcional al resultado
     * @return intento terminal con trazabilidad de propiedad conservada
     */
    public AttemptSnapshot completeAttempt(
            ExecutionActivityService.Ownership owner,
            String resultReference
    ) {
        return finish(
                owner,
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
     *
     * @param owner credenciales de la adquisición vigente
     * @param outcome resultado conocido de efectos
     * @param errorReference referencia obligatoria al incidente
     * @return intento FAILED confirmado
     */
    public AttemptSnapshot failAttempt(
            ExecutionActivityService.Ownership owner,
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
                owner,
                ExecutionStatus.FAILED,
                outcome,
                null,
                requireText(errorReference, "errorReference", 500)
        );
    }

    /**
     * Registra un resultado incierto que no permite
     * reintentos automáticos.
     *
     * @param owner credenciales de la adquisición vigente
     * @param errorReference referencia obligatoria al incidente
     * @return intento UNKNOWN confirmado, o rechazo si la propiedad venció
     */
    public AttemptSnapshot markUnknown(
            ExecutionActivityService.Ownership owner,
            String errorReference
    ) {
        return finish(
                owner,
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
            ExecutionActivityService.Ownership owner,
            ExecutionStatus next,
            EffectOutcome outcome,
            String resultReference,
            String errorReference
    ) {
        Objects.requireNonNull(owner, "owner es obligatorio");
        UUID executionId = owner.executionId();

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

                    Instant now = activity.databaseNow();
                    if (!owner.executorId().equals(current.executorId())
                            || !Objects.equals(owner.fencingToken(), current.fencingToken())
                            || current.leaseExpiresAt() == null || !current.leaseExpiresAt().isAfter(now)) {
                        throw new AttemptDeniedException("IR-009: propiedad ausente o vencida al finalizar");
                    }
                    int updated = jdbc.update(
                            """
                            UPDATE execution_attempt
                            SET execution_status = ?,
                                effect_outcome = ?,
                                completed_at = ?,
                                result_reference = ?,
                                error_reference = ?,
                                version = version + 1
                            WHERE execution_id = ?
                              AND execution_status = ?
                              AND version = ? AND executor_id = ? AND fencing_token = ?
                              AND lease_expires_at > ?
                            """,
                            next.name(),
                            outcome.name(),
                            Timestamp.from(now),
                            result,
                            errorReference,
                            executionId,
                            ExecutionStatus.IN_PROGRESS.name(),
                            current.version(), owner.executorId(), owner.fencingToken(), Timestamp.from(now)
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
     * Inicia un intento PENDING después de revalidar su planificación y
     * la autorización vigente. Es el único punto de inicio del servicio.
     *
     * <p>Confirma IN_PROGRESS, started_at y el incremento de version en
     * una transacción REQUIRES_NEW. Bloquea primero el plan, después el
     * intento y finalmente la autorización. Un rechazo revierte los cambios.
     *
     * <p>No ejecuta efectos externos. La validación no garantiza protección
     * frente a revocaciones posteriores a la confirmación de la transacción.
     *
     * @param executionId identificador del intento persistido
     * @param actionType tipo que debe coincidir con la acción en el plan actual
     * @return intento confirmado en IN_PROGRESS
     * @throws NullPointerException si algún argumento es nulo
     * @throws IllegalArgumentException si el tipo está vacío, supera 100
     *         caracteres o el contrato JSON del plan es inválido
     * @throws AttemptDeniedException si el intento no existe, no está PENDING,
     *         la acción no pertenece al plan o la autorización no es válida
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
            if(jdbc.queryForObject("SELECT COUNT(*) FROM execution_pending_review WHERE execution_id=?",Integer.class,executionId)>0) {
                throw new AttemptDeniedException("IR-009: intento cancelado antes del inicio");
            }
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
                            activity.databaseNow()
                    )
            );
            if (!authorized.allowed()) {
                throw new AttemptDeniedException(
                        "IR-010: autorización inválida: " + authorized.reason()
                );
            }
            Instant now = activity.databaseNow();
            long token = activity.nextToken();
            requireUpdated(jdbc.update(
                    """
                    UPDATE execution_attempt
                    SET execution_status = 'IN_PROGRESS',
                        started_at = ?, executor_id = ?, fencing_token = ?,
                        heartbeat_at = ?, lease_expires_at = ?,
                        version = version + 1
                    WHERE execution_id = ? AND execution_status = 'PENDING'
                      AND version = ?
                    """,
                    Timestamp.from(now), activity.executorId(), token,
                    Timestamp.from(now), Timestamp.from(now.plus(activity.leaseDuration())),
                    executionId, current.version()
            ));
            return lockAttempt(executionId);
        }));
    }
    /**
     * Revalida plan, identidad, alcance, vigencia, huella, solicitud y propiedad del intento después del
     * lookup. Cierra plan → intento → autorización antes de volver al callback de envío.
     * Solo el coordinador confiable utiliza esta guarda; no renueva permisos ni actividad.
     */
    void validateBeforeSend(AuthorizedActionExecutionService.ExecutionCommand command,
            ExecutionActivityService.Ownership owner,UUID operationKey,ExecutionRequest request) {
        transaction.executeWithoutResult(status->{
            var plan=lockAndReadPlan(command.planId()); var current=lockAttempt(owner.executionId());
            if(!current.planId().equals(command.planId())||!current.cycleId().equals(command.cycleId())
                    ||!current.plannedActionId().equals(command.actionId())||!current.authorizationId().equals(command.authorizationId())
                    ||!plannedPolicy.evaluate(plan,new ActionRequest(command.planId(),command.cycleId(),command.actionId(),command.actionType())).allowed())
                throw dispatchDenied(ExecutionDispatchRejectedException.Code.ATTEMPT_CONTEXT_CHANGED,owner);
            lockAuthorization(command.authorizationId());
            var metadata=jdbc.queryForMap("SELECT created_by,status FROM intervention_plan WHERE plan_id=?",command.planId());
            String approval=jdbc.queryForObject("SELECT request_fingerprint FROM authorizations WHERE authorization_id=?",String.class,command.authorizationId());
            if(metadata.get("CREATED_BY")!=null&&(approval==null||!"APPROVED".equals(metadata.get("STATUS"))))
                throw dispatchDenied(ExecutionDispatchRejectedException.Code.APPROVED_CONTENT_CHANGED,owner);
            if(approval!=null) {
                String fingerprint=jdbc.queryForObject("SELECT * FROM intervention_plan WHERE plan_id=?",(rs,row)->PlannedContentFingerprint.of(rs),command.planId());
                if(!approval.equals(fingerprint)) throw dispatchDenied(ExecutionDispatchRejectedException.Code.APPROVED_CONTENT_CHANGED,owner);
            }
            if(operationKey!=null) {
                UUID persisted=jdbc.queryForObject("SELECT operation_key FROM execution_attempt WHERE execution_id=?",UUID.class,owner.executionId());
                var requests=jdbc.query("SELECT destination_id,request_media_type,request_body,request_fingerprint FROM execution_operation WHERE operation_key=? AND plan_id=? AND planned_action_id=?",
                        (rs,row)-> {
                            if(rs.getString(2)==null||rs.getString(3)==null) return null;
                            var captured=new ExecutionRequest(rs.getString(1),rs.getString(2),rs.getString(3));
                            return captured.fingerprint().equals(rs.getString(4))?captured:null;
                        },operationKey,command.planId(),command.actionId());
                if(!operationKey.equals(persisted)||request==null||requests.size()!=1||!request.equals(requests.getFirst()))
                    throw dispatchDenied(ExecutionDispatchRejectedException.Code.REQUEST_CHANGED,owner);
                String document=jdbc.queryForObject("SELECT actions FROM intervention_plan WHERE plan_id=?",String.class,command.planId());
                var approvedRequest=plannedJson.executionRequest(command.actionId(),document);
                if(approvedRequest.isPresent()&&!approvedRequest.get().equals(request))
                    throw dispatchDenied(ExecutionDispatchRejectedException.Code.REQUEST_CHANGED,owner);
            }
            Instant now=activity.databaseNow().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(current.status()!=ExecutionStatus.IN_PROGRESS)
                throw dispatchDenied(ExecutionDispatchRejectedException.Code.ATTEMPT_NOT_RUNNING,owner);
            if(!owner.executorId().equals(current.executorId())||!Objects.equals(owner.fencingToken(),current.fencingToken()))
                throw dispatchDenied(ExecutionDispatchRejectedException.Code.ATTEMPT_OWNER_CHANGED,owner);
            if(current.leaseExpiresAt()==null||!current.leaseExpiresAt().isAfter(now))
                throw dispatchDenied(ExecutionDispatchRejectedException.Code.ATTEMPT_LEASE_EXPIRED,owner);
            if(!authorizations.evaluate(command.authorizationId(),new AuthorizationUseRequest(command.planId(),command.actionId(),EXECUTE_SCOPE,now)).allowed())
                throw dispatchDenied(ExecutionDispatchRejectedException.Code.AUTHORIZATION_INVALID,owner);
        });
    }

    /** Construye un rechazo estable sin exponer contenido o mensajes de base de datos. */
    private static ExecutionDispatchRejectedException dispatchDenied(ExecutionDispatchRejectedException.Code code,ExecutionActivityService.Ownership owner) {
        return new ExecutionDispatchRejectedException(code,owner.executionId(),null);
    }

    /**
     * Registra rechazo anterior al efecto externo. Nunca se invoca
     * después de que el ejecutor haya comenzado.
     * Una cancelación auditada ya existente se conserva sin reescribir sus fechas.
     */
    public AttemptSnapshot rejectPendingBeforeExecution(
            UUID executionId, String errorReference
    ) {
        Objects.requireNonNull(executionId, "executionId es obligatorio");
        String reference = requireText(errorReference, "errorReference", 500);
        return Objects.requireNonNull(transaction.execute(tx -> {
            AttemptSnapshot current = lockAttempt(executionId);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM execution_pending_review WHERE execution_id=?",Integer.class,executionId)>0) {
                return current;
            }
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

