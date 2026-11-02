package com.miec.action;

import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Registra resoluciones únicas y auditadas sin sobrescribir estados de ejecución. */
@Service
public class ExecutionReconciliationService {
    /** Solicitud atribuida a un revisor autorizado y respaldada por una evidencia concreta. */
    public record Command(UUID executionId,UUID evidenceId,ExecutionReconciliationPolicy.Resolution resolution,
            UUID authorizationId,String reviewer,String rationale) {
        /** Valida identidades y normaliza los textos antes de abrir transacciones. */
        public Command {
            Objects.requireNonNull(executionId);Objects.requireNonNull(evidenceId);
            Objects.requireNonNull(resolution);Objects.requireNonNull(authorizationId);
            reviewer=ExecutionRecoveryAccess.text(reviewer,255);
            rationale=ExecutionRecoveryAccess.text(rationale,1000);
        }
    }
    /** Decisión inmutable con identidad y tiempo de confirmación. */
    public record Decision(UUID id,Command command,String originalStatus,Instant decidedAt) { }
    private final ExecutionRecoveryAccess access;
    private final ExecutionEvidenceService evidence;
    private final ExecutionReconciliationPolicy policy;

    /** Configura la persistencia y las reglas conservadoras de reconciliación. */
    public ExecutionReconciliationService(ExecutionRecoveryAccess access,ExecutionEvidenceService evidence,
            ExecutionReconciliationPolicy policy) { this.access=access;this.evidence=evidence;this.policy=policy; }

    /**
     * Revalida autorización, bloquea plan/intento y confirma una sola decisión.
     * Una repetición idéntica devuelve la decisión previa; un cambio se rechaza.
     */
    public Decision decide(Command command) {
        Objects.requireNonNull(command);
        return Objects.requireNonNull(access.transaction.execute(tx->{
            var attempt=access.lock(command.executionId());
            access.authorize(attempt,command.authorizationId(),command.reviewer());
            var old=find(command.executionId());
            if(old.isPresent()) {
                if(!old.get().command().equals(command)) throw ExecutionRecoveryAccess.denied("El intento ya tiene otra decisión");
                return old.get();
            }
            validate(attempt,command);
            Instant now=access.activity.databaseNow().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            UUID id=UUID.randomUUID();
            access.jdbc.update("""
                    INSERT INTO execution_reconciliation(decision_id,execution_id,evidence_id,resolution,
                        reviewed_by,authorization_id,rationale,original_status,decided_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,id,command.executionId(),command.evidenceId(),command.resolution().name(),command.reviewer(),
                    command.authorizationId(),command.rationale(),attempt.status(),Timestamp.from(now));
            return new Decision(id,command,attempt.status(),now);
        }));
    }

    /** Comprueba de nuevo toda la evidencia, incluida la recibida después de decidir. */
    private void validate(ExecutionRecoveryAccess.Attempt attempt,Command command) {
        List<ExecutionEvidenceService.Evidence> reports=evidence.findByAttempt(attempt.id());
        var selected=reports.stream().filter(e->e.id().equals(command.evidenceId())).findFirst()
                .orElseThrow(()->ExecutionRecoveryAccess.denied("Evidencia de otro intento o inexistente"));
        var expected=command.resolution()==ExecutionReconciliationPolicy.Resolution.APPLIED
                ?ExecutionEvidenceService.Outcome.EFFECT_APPLIED:ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED;
        boolean conflict=reports.stream().anyMatch(e->e.outcome()!=ExecutionEvidenceService.Outcome.EFFECT_UNKNOWN && e.outcome()!=expected);
        policy.validate(attempt.status(),attempt.lease(),access.activity.databaseNow(),command.resolution(),
                selected.source()==ExecutionEvidenceService.Source.DESTINATION_VERIFIED,selected.outcome(),conflict);
    }

    /** Consulta la decisión persistida sin modificar el intento. */
    public Optional<Decision> find(UUID executionId) {
        return access.jdbc.query("SELECT * FROM execution_reconciliation WHERE execution_id=?",(rs,row)->
                new Decision(rs.getObject("decision_id",UUID.class),new Command(rs.getObject("execution_id",UUID.class),
                        rs.getObject("evidence_id",UUID.class),ExecutionReconciliationPolicy.Resolution.valueOf(rs.getString("resolution")),
                        rs.getObject("authorization_id",UUID.class),rs.getString("reviewed_by"),rs.getString("rationale")),
                        rs.getString("original_status"),rs.getTimestamp("decided_at").toInstant()),Objects.requireNonNull(executionId))
                .stream().findFirst();
    }

    /**
     * Consulta interna bajo el bloqueo del plan tomado por createAttempt.
     * No permite por sí sola ejecutar: la ruta protegida exige además la clave
     * original del destinatario y vuelve a validar la autorización de ejecución.
     */
    boolean permitsProtectedRetry(UUID executionId) {
        var decision=find(executionId);
        if(decision.isEmpty()||decision.get().command().resolution()!=ExecutionReconciliationPolicy.Resolution.NO_EFFECT) return false;
        var attempt=access.lock(executionId);
        try { validate(attempt,decision.get().command()); return true; }
        catch(ExecutionAttemptService.AttemptDeniedException denied) { return false; }
    }
}
