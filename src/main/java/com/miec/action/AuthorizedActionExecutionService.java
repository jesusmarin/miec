package com.miec.action;

import com.miec.action.ExecutionAttemptService.AttemptDeniedException;
import com.miec.action.ExecutionAttemptService.AttemptSnapshot;
import com.miec.action.ExecutionAttemptService.CreateAttemptCommand;
import com.miec.action.ExecutionRetryPolicy.ExecutionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * MIEC-F03 IR-009: punto único de entrada para ejecución autorizada.
 *
 * <p>Ambos métodos públicos delegan en el mismo flujo de intentos:
 * {@code execution_attempt}. No se generan nuevos registros en
 * {@code executed_action}; sus registros históricos son protegidos
 * por ExecutionAttemptService durante la creación del intento.
 *
 * <p>Los efectos externos no son atómicos con la auditoría JDBC.
 * La recuperación de resultados inciertos requiere reconciliación.
 */
@Service
public class AuthorizedActionExecutionService {

    private final ExecutionAttemptService attemptService;
    private final ExecutionHeartbeatSupervisor heartbeatSupervisor;
    private final ExecutionEvidenceService evidenceService;
    private final ExecutionIncidentService incidents;

    /** Contrato de operación síncrona administrada por el coordinador. */
    @FunctionalInterface
    public interface ActionExecutor {
        void execute();
    }

    /** Solicitud de ejecución autorizada. */
    public record ExecutionCommand(
            UUID actionId,
            UUID planId,
            UUID cycleId,
            UUID authorizationId,
            String actionType,
            String actorId
    ) {
        public ExecutionCommand {
            Objects.requireNonNull(actionId, "actionId es obligatorio");
            Objects.requireNonNull(planId, "planId es obligatorio");
            Objects.requireNonNull(cycleId, "cycleId es obligatorio");
            Objects.requireNonNull(authorizationId, "authorizationId es obligatorio");
            actionType = requireText(actionType, "actionType", 100);
            actorId = requireText(actorId, "actorId", 255);
        }
    }

    /** Respuesta compatible con el método anterior. */
    public record ExecutionResult(UUID actionId, String status) {
    }

    /** Resultado que expone la identidad del intento. */
    public record TrackedExecutionResult(
            UUID executionId,
            UUID actionId,
            int attemptNumber,
            String status
    ) {
    }

    /** Rechazo por incumplimiento de las reglas de ejecución. */
    public static class ExecutionDeniedException extends RuntimeException {
        public ExecutionDeniedException(String message) {
            super(message);
        }

        public ExecutionDeniedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public AuthorizedActionExecutionService(
            ExecutionAttemptService attemptService,
            ExecutionHeartbeatSupervisor heartbeatSupervisor,
            ExecutionEvidenceService evidenceService,
            ExecutionIncidentService incidents
    ) {
        this.heartbeatSupervisor = Objects.requireNonNull(heartbeatSupervisor);
        this.evidenceService = Objects.requireNonNull(evidenceService);
        this.incidents=Objects.requireNonNull(incidents);
        this.attemptService = Objects.requireNonNull(
                attemptService, "ExecutionAttemptService es obligatorio"
        );
    }

    private static String requireText(String value, String field, int maximum) {
        Objects.requireNonNull(value, field + " es obligatorio");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " debe contener entre 1 y " + maximum + " caracteres"
            );
        }
        return normalized;
    }

    /**
     * Contrato heredado: ahora usa exactamente el mismo flujo que el
     * método con trazabilidad. Ya no escribe en executed_action.
     */
    public ExecutionResult executeAuthorized(
            ExecutionCommand command,
            ActionExecutor executor
    ) {
        TrackedExecutionResult result = executeAuthorizedTracked(command, executor);
        return new ExecutionResult(result.actionId(), result.status());
    }

    /**
     * Coordina un intento de ejecución autorizado.
     *
     * <p>No admite una transacción exterior activa: la auditoría usa
     * transacciones independientes REQUIRES_NEW y una transacción de
     * negocio exterior podría revertirse después de registrar COMPLETED.
     *
     * <p>La autorización se revalida en startAuthorizedAttempt() y en la
     * admisión final anterior al envío, después de cualquier lookup. Una revocación
     * posterior al inicio no puede impedir por sí sola efectos externos:
     * ese caso requiere coordinación con el destinatario.
     *
     * @throws ExecutionDeniedException si la política deniega el intento
     * @throws RuntimeException si falla la ejecución o la persistencia
     */
    public TrackedExecutionResult executeAuthorizedTracked(
            ExecutionCommand command,
            ActionExecutor executor
    ) {
        Objects.requireNonNull(executor, "executor es obligatorio");
        return executeTracked(command, null, (owner,admission) -> admission.send(null,()->{},()->{ executor.execute(); return null; }));
    }

    /** Callback interno con credenciales, frontera de admisión y referencia al resultado del destinatario. */
    @FunctionalInterface
    interface OwnedExecutor {
        /** Ejecuta una operación bajo la adquisición actual. */
        String execute(ExecutionActivityService.Ownership owner,DispatchAdmission admission);
    }

    /** Ruta interna que exige la clave idempotente registrada desde el primer intento. */
    TrackedExecutionResult executeProtected(ExecutionCommand command, UUID operationKey, OwnedExecutor executor) {
        return executeTracked(command, Objects.requireNonNull(operationKey), executor);
    }

    /** Coordina efectos, supervisión y evidencia sin inferir ausencia de un fallo. */
    private TrackedExecutionResult executeTracked(ExecutionCommand command, UUID operationKey, OwnedExecutor executor) {
        Objects.requireNonNull(command, "command es obligatorio");
        Objects.requireNonNull(executor, "executor es obligatorio");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "IR-009: no se admite una transacción externa activa"
            );
        }

        CreateAttemptCommand request = new CreateAttemptCommand(
                command.actionId(),
                command.planId(),
                command.cycleId(),
                command.authorizationId(),
                command.actionType(),
                command.actorId()
        );

        final AttemptSnapshot pending;
        try {
            pending = operationKey == null ? attemptService.createAttempt(request)
                    : attemptService.createProtectedAttempt(request, operationKey);
        } catch (AttemptDeniedException ex) {
            throw new ExecutionDeniedException("IR-009: " + ex.getMessage(), ex);
        }

        final AttemptSnapshot started;
        try {
            // Revalida plan, acción y autorización con bloqueos JDBC.
            started = attemptService.startAuthorizedAttempt(
                    pending.executionId(), command.actionType()
            );
            if (started.status() != ExecutionStatus.IN_PROGRESS) {
                throw new ExecutionDeniedException(
                        "IR-009: el intento no alcanzó IN_PROGRESS"
                );
            }
        } catch (RuntimeException startFailure) {
            // Aún no se llamó al ejecutor: se puede confirmar ausencia
            // de efectos del intento actual sin deducirla de un timeout.
            try {
                attemptService.rejectPendingBeforeExecution(
                        pending.executionId(),
                        "start-rejected:" + pending.executionId()
                );
            } catch (RuntimeException auditFailure) {
                startFailure.addSuppressed(auditFailure);
            }
            incidents.captureCore(pending.executionId(),ExecutionIncidentService.Phase.REJECTED_BEFORE_SEND,null,startFailure);
            if (startFailure instanceof AttemptDeniedException) {
                throw new ExecutionDeniedException(
                        "IR-009: " + startFailure.getMessage(), startFailure
                );
            }
            throw startFailure;
        }

        String resultReference;
        boolean appliedObserved=false;
        var admission=new DispatchAdmission(attemptService,command,started.ownership(),operationKey);
        try (var supervision = heartbeatSupervisor.supervise(started.ownership())) {
            admission.supervise(supervision::checkHealthy);
            try {
                resultReference = executor.execute(started.ownership(),admission);
                appliedObserved=true;
                recordReport(started.ownership(), ExecutionEvidenceService.Outcome.EFFECT_APPLIED,
                        resultReference == null ? "execution-result:" + started.executionId() : resultReference);
            } catch (RuntimeException | Error executionFailure) {
                supervision.close();
                supervision.attachFailure(executionFailure);
                throw executionFailure;
            }
            supervision.close();
            supervision.checkHealthy();
        } catch (RuntimeException | Error failure) {
            try {
                recordReport(started.ownership(), ExecutionEvidenceService.Outcome.EFFECT_UNKNOWN,
                        "execution-incident:" + started.executionId());
            } catch (RuntimeException reportFailure) { failure.addSuppressed(reportFailure); }
            // La excepción NO demuestra que el efecto externo no sucedió.
            try {
                attemptService.markUnknown(
                        started.ownership(),
                        "execution-failure:" + started.executionId()
                );
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            var phase=admission.rejectedBeforeSend()?ExecutionIncidentService.Phase.REJECTED_BEFORE_SEND:
                    appliedObserved?ExecutionIncidentService.Phase.APPLIED_CONFIRMATION_FAILED:ExecutionIncidentService.Phase.OUTCOME_UNCERTAIN;
            incidents.captureCore(started.executionId(),phase,admission.rejectedBeforeSend()?admission.rejection().code():null,failure);
            throw failure;
        }

        final AttemptSnapshot completed;
        try {
            completed = attemptService.completeAttempt(started.ownership(), resultReference);
        } catch (RuntimeException persistenceFailure) {
            // El ejecutor ya pudo producir efectos. Bloquear reintentos;
            // si la persistencia de UNKNOWN falla, quedará IN_PROGRESS.
            try {
                attemptService.markUnknown(
                        started.ownership(),
                        "completion-uncertain:" + started.executionId()
                );
            } catch (RuntimeException auditFailure) {
                persistenceFailure.addSuppressed(auditFailure);
            }
            incidents.captureCore(started.executionId(),ExecutionIncidentService.Phase.APPLIED_CONFIRMATION_FAILED,null,persistenceFailure);
            throw persistenceFailure;
        }

        return new TrackedExecutionResult(
                completed.executionId(),
                completed.plannedActionId(),
                completed.attemptNumber(),
                completed.status().name()
        );
    }
    /** Persiste reporte deduplicable incluso si venció el lease; nunca se marca como verificado. */
    private void recordReport(ExecutionActivityService.Ownership owner, ExecutionEvidenceService.Outcome outcome,
            String reference) {
        UUID eventId = UUID.nameUUIDFromBytes((owner.executionId() + ":" + outcome.name())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        evidenceService.reportObservation(eventId, owner, outcome, reference);
    }

}
