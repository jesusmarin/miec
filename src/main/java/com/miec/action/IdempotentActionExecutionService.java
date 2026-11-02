package com.miec.action;

import org.springframework.stereotype.Service;
import java.util.Objects;

/** Única ruta de reintento de incertidumbre reconciliada con protección del destinatario. */
@Service
public class IdempotentActionExecutionService {
    private final ExecutionOperationRegistry operations;
    private final AuthorizedActionExecutionService coordinator;
    private final ExecutionOutcomeQueryService outcomes;

    /** Combina identidad durable y el coordinador autorizado con heartbeat. */
    public IdempotentActionExecutionService(ExecutionOperationRegistry operations,AuthorizedActionExecutionService coordinator,
            ExecutionOutcomeQueryService outcomes) {
        this.operations=operations;this.coordinator=coordinator;this.outcomes=outcomes;
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
        var request=Objects.requireNonNull(executor.request());
        var operation=operations.prepare(command,request);
        return coordinator.executeProtected(command,operation.key(),owner->{
            var context=new IdempotentActionExecutor.Context(operation,owner,request);
            var lookup=Objects.requireNonNull(executor.lookup(context));
            return switch(lookup.status()) {
                case APPLIED -> lookup.resultReference();
                case UNKNOWN -> throw ExecutionRecoveryAccess.denied("El destinatario no confirma el resultado");
                case NOT_FOUND -> ExecutionRecoveryAccess.text(executor.execute(context),500);
            };
        });
    }

    /** Resultado conocido de una clave; conserva el estado original y la resolución que lo respalda. */
    public record KnownResult(java.util.UUID operationKey,java.util.UUID executionId,String reference,
            String originalStatus,String resolution) { }

    /**
     * Recupera un resultado aplicado y sin contradicciones sin crear intentos,
     * reservar claves ni llamar al destinatario. Devuelve vacío si aún no hay
     * referencia confirmada; una autorización inválida se rechaza explícitamente.
     */
    public java.util.Optional<KnownResult> recoverResult(AuthorizedActionExecutionService.ExecutionCommand command) {
        var operation=operations.findAuthorized(command);
        if(operation.isEmpty()) return java.util.Optional.empty();
        return outcomes.findLatest(command.planId(),command.actionId())
                .filter(view->operation.get().key().equals(view.operationKey()))
                .filter(view->view.outcome()==ExecutionOutcomeQueryService.EffectiveOutcome.APPLIED)
                .filter(view->view.resultReference()!=null)
                .map(view->new KnownResult(operation.get().key(),view.executionId(),view.resultReference(),
                        view.originalStatus(),view.resolution()));
    }
}
