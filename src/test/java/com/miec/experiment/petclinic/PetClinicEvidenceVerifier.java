package com.miec.experiment.petclinic;

import com.miec.action.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Verifica resultados aplicados del destinatario autenticado; nunca certifica ausencia a partir de un 404. */
public class PetClinicEvidenceVerifier {
    private final JdbcTemplate jdbc;
    private final ExecutionEvidenceService evidence;
    private final ExecutionActivityService activity;

    /** Configura el vínculo local del intento y el registro autorizado de evidencia. */
    public PetClinicEvidenceVerifier(JdbcTemplate jdbc,ExecutionEvidenceService evidence,ExecutionActivityService activity) {
        this.jdbc=jdbc;this.evidence=evidence;this.activity=activity;
    }

    /**
     * Consulta fuera de bloqueos JDBC y acepta solo una respuesta APPLIED ligada
     * a la clave/huella persistidas. verified revalida el permiso del revisor al
     * guardar la evidencia; la fecha del hecho aquí es el momento de verificación.
     */
    public ExecutionEvidenceService.Evidence verifyApplied(UUID evidenceId,PetClinicVisitExecutor executor,
            IdempotentActionExecutor.Context context,UUID authorizationId,String reviewer) {
        int matches=jdbc.queryForObject("""
                SELECT COUNT(*) FROM execution_attempt a JOIN execution_operation o ON o.operation_key=a.operation_key
                WHERE a.execution_id=? AND a.executor_id=? AND a.fencing_token=?
                  AND o.operation_key=? AND o.request_fingerprint=? AND o.destination_id=?
                """,Integer.class,context.ownership().executionId(),context.ownership().executorId(),context.ownership().fencingToken(),
                context.operation().key(),context.request().fingerprint(),context.request().destinationId());
        if(matches!=1) throw new ExecutionAttemptService.AttemptDeniedException("Contexto de verificación no corresponde al intento");
        var lookup=executor.lookup(context);
        if(lookup.status()!=IdempotentActionExecutor.Status.APPLIED) throw new ExecutionAttemptService.AttemptDeniedException("PetClinic no confirma aplicación");
        return evidence.verified(evidenceId,context.ownership().executionId(),authorizationId,reviewer,
                ExecutionEvidenceService.Outcome.EFFECT_APPLIED,lookup.resultReference(),ExecutionRecoveryTestSupport.databaseNow(activity));
    }
}
