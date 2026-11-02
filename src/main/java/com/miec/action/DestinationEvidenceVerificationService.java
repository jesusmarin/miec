package com.miec.action;

import com.miec.action.integration.DestinationEvidenceSource;
import com.miec.action.integration.DestinationEvidenceSources;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Objects;
import java.util.UUID;

/** Verificación común mediante fuentes confiables; independiente del protocolo y dominio externos. */
@Service
public class DestinationEvidenceVerificationService {
    private final ExecutionRecoveryAccess access;
    private final ExecutionEvidenceService evidence;
    private final DestinationEvidenceSources sources;

    /** Recibe infraestructura local y el registro fijo de fuentes del servidor. */
    public DestinationEvidenceVerificationService(ExecutionRecoveryAccess access, ExecutionEvidenceService evidence,
                                                DestinationEvidenceSources sources) {
        this.access = access;
        this.evidence = evidence;
        this.sources = sources;
    }

    /** Datos del solicitante; endpoint, adaptador, clave y solicitud se obtienen del servidor. */
    public record Command(UUID evidenceId, UUID executionId, UUID authorizationId, String reviewer) {
        /** Exige identificadores y responsable antes de acceder a la persistencia. */
        public Command {
            Objects.requireNonNull(evidenceId);
            Objects.requireNonNull(executionId);
            Objects.requireNonNull(authorizationId);
            reviewer = ExecutionRecoveryAccess.text(reviewer, 255);
        }
    }

    /**
     * Autoriza y reconstruye antes de consultar sin bloqueos. Solo APPLIED vinculado
     * al snapshot genera evidencia; verified revalida la autorización al persistir.
     * La fecha representa la observación local, no el instante remoto del efecto.
     */
    public ExecutionEvidenceService.Evidence verifyApplied(Command command) {
        Objects.requireNonNull(command);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("IR-009: no se admite una transacción externa activa");
        }
        var context = Objects.requireNonNull(access.transaction.execute(tx -> {
            var attempt = access.lock(command.executionId());
            access.authorize(attempt, command.authorizationId(), command.reviewer());
            if (attempt.startedAt() == null || attempt.executor() == null || attempt.token() == null) {
                throw ExecutionRecoveryAccess.denied("El intento no tiene una adquisición iniciada");
            }
            var operations = access.jdbc.query("""
                    SELECT o.* FROM execution_operation o
                    JOIN execution_attempt a ON a.operation_key=o.operation_key
                    WHERE a.execution_id=? AND o.plan_id=? AND o.planned_action_id=?
                    """, (rs, row) -> {
                if (!"UTF8-V1".equals(rs.getString("request_format"))) {
                    throw ExecutionRecoveryAccess.denied("La operación no conserva una solicitud verificable");
                }
                var request = new ExecutionRequest(rs.getString("destination_id"),
                        rs.getString("request_media_type"), rs.getString("request_body"));
                return new ExecutionOperationRegistry.Operation(rs.getObject("operation_key", UUID.class),
                        attempt.plan(), attempt.action(), rs.getString("destination_id"),
                        rs.getString("request_fingerprint"), request);
            }, attempt.id(), attempt.plan(), attempt.action());
            if (operations.size() != 1) throw ExecutionRecoveryAccess.denied("Intento sin operación verificable");
            var operation = operations.getFirst();
            return new IdempotentActionExecutor.Context(operation,
                    new ExecutionActivityService.Ownership(attempt.id(), attempt.executor(), attempt.token()),
                    operation.request());
        }));

        DestinationEvidenceSource source = sources.require(context.request().destinationId());
        var observed = Objects.requireNonNull(source.lookup(context));
        if (!context.operation().key().equals(observed.operationKey())
                || !context.request().destinationId().equals(observed.destinationId())
                || !context.request().fingerprint().equals(observed.fingerprint())
                || observed.status() != IdempotentActionExecutor.Status.APPLIED) {
            throw ExecutionRecoveryAccess.denied("El destinatario no confirma una aplicación vinculada a la operación");
        }
        return evidence.verifiedObservation(command.evidenceId(), command.executionId(), command.authorizationId(),
                command.reviewer(), observed.resultReference());
    }
}
