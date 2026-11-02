package com.miec.action;

import org.springframework.stereotype.Service;
import java.util.Objects;

/** Única ruta de reintento de incertidumbre reconciliada con protección del destinatario. */
@Service
public class IdempotentActionExecutionService {
    private final ExecutionOperationRegistry operations;
    private final AuthorizedActionExecutionService coordinator;

    /** Combina identidad durable y el coordinador autorizado con heartbeat. */
    public IdempotentActionExecutionService(ExecutionOperationRegistry operations,AuthorizedActionExecutionService coordinator) {
        this.operations=operations;this.coordinator=coordinator;
    }

    /**
     * Consulta primero al destinatario usando la clave estable. APPLIED devuelve
     * su referencia sin ejecutar; UNKNOWN se rechaza; NOT_FOUND permite enviar
     * únicamente bajo el contrato durable de idempotencia del adaptador.
     */
    public AuthorizedActionExecutionService.TrackedExecutionResult execute(
            AuthorizedActionExecutionService.ExecutionCommand command,IdempotentActionExecutor executor) {
        Objects.requireNonNull(executor);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("IR-009: no se admite una transacción externa activa");
        }
        var operation=operations.prepare(command,executor.destinationId(),executor.requestFingerprint());
        return coordinator.executeProtected(command,operation.key(),owner->{
            var context=new IdempotentActionExecutor.Context(operation,owner);
            var lookup=Objects.requireNonNull(executor.lookup(context));
            return switch(lookup.status()) {
                case APPLIED -> lookup.resultReference();
                case UNKNOWN -> throw ExecutionRecoveryAccess.denied("El destinatario no confirma el resultado");
                case NOT_FOUND -> ExecutionRecoveryAccess.text(executor.execute(context),500);
            };
        });
    }
}
