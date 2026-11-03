package com.miec.orchestration;

import com.miec.action.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import static com.miec.orchestration.PlanExecutionContracts.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/**
 * Recuperación remota explícita del MVP. Consulta una fuente instalada, conserva
 * evidencia y decisión IR-009 y actualiza la proyección local. Nunca invoca execute,
 * renueva permisos ni concluye que NOT_FOUND significa ausencia del efecto.
 */
@Service
public class PlanRecoveryApplicationService {
    private final InterventionIdentity identity;
    private final PlanRecoveryStore store;
    private final DestinationEvidenceVerificationService verifier;
    private final ExecutionReconciliationService reconciliation;
    private final PlanExecutionStore runs;

    /** Usa el verificador confiable existente y transacciones breves independientes. */
    PlanRecoveryApplicationService(InterventionIdentity identity,PlanRecoveryStore store,
            DestinationEvidenceVerificationService verifier,ExecutionReconciliationService reconciliation,PlanExecutionStore runs) {
        this.identity=identity;this.store=store;this.verifier=verifier;this.reconciliation=reconciliation;this.runs=runs;
    }

    /** AUTHOR solicita un permiso separado para el intento incierto del mismo plan. */
    public InterventionViews.Authorization request(UUID planId,RequestReconciliation command) {
        return store.request(planId,command,identity.author());
    }

    /** APPROVER distinto del solicitante aprueba o rechaza una solicitud sin reactivar ejecución. */
    public InterventionViews.Authorization decide(UUID id,InterventionCommands.Decide command,boolean approve) {
        return store.decide(id,command,approve,identity.approver());
    }

    /**
     * Comprueba versiones antes/después de la red y revalida autorización al guardar
     * cada hecho. Si el run cambia durante la consulta, la evidencia ya confirmada
     * permanece, pero el comando no sobreescribe la revisión nueva. Un reintento
     * explícito con versiones actuales reutiliza evidencia y decisión deterministas.
     * No cambia a E08: la revisión final utiliza el comando finish separado.
     */
    public Run reconcile(UUID planId,Reconcile command) {
        String actor=identity.approver();
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw fail(CONFLICT,"OUTER_TRANSACTION_NOT_ALLOWED","La consulta remota requiere una llamada sin transacción exterior");
        var review=store.prepare(planId,command,actor);
        try {
            verifier.verifyApplied(new DestinationEvidenceVerificationService.Command(review.evidenceId(),review.executionId(),command.authorizationId(),actor));
            var current=store.prepare(planId,command,actor);
            if(!review.equals(current)) throw fail(CONFLICT,"ATTEMPT_CHANGED","Cambió el intento durante la consulta");
            reconciliation.decide(new ExecutionReconciliationService.Command(review.executionId(),review.evidenceId(),
                    ExecutionReconciliationPolicy.Resolution.APPLIED,command.authorizationId(),actor,command.reason()));
        } catch(ExecutionAttemptService.AttemptDeniedException rejected) {
            throw fail(CONFLICT,"DESTINATION_NOT_CONFIRMED","No se confirmó evidencia aplicable sin contradicciones y con autorización vigente");
        } catch(IllegalStateException unavailable) {
            throw fail(CONFLICT,"DESTINATION_NOT_CONFIRMED","El destinatario configurado no confirmó el efecto; se conserva la incertidumbre");
        }
        return runs.recover(planId,new Control(command.expectedVersion(),command.reason()),actor);
    }
}
