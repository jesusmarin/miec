package com.miec.action;

import org.springframework.stereotype.Service;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Recibe evidencia inmutable sin renovar propiedad ni resolver el intento. */
@Service
public class ExecutionEvidenceService {
    /** Diferencia reportes de la adquisición y verificaciones autorizadas del destinatario. */
    public enum Source { EXECUTOR_REPORT, DESTINATION_VERIFIED }
    /** Evidencia de aplicación, ausencia confirmada o incertidumbre. */
    public enum Outcome { EFFECT_APPLIED, NO_EFFECT_CONFIRMED, EFFECT_UNKNOWN }
    /** Contenido inmutable y su fecha de recepción establecida por la base de datos. */
    public record Evidence(UUID id,UUID executionId,Source source,String reportedBy,String executorId,
            Long fencingToken,Outcome outcome,String reference,Instant occurredAt,Instant receivedAt,UUID authorizationId) { }
    private final ExecutionRecoveryAccess access;

    /** Recibe infraestructura compartida con bloqueos y reloj de base de datos. */
    public ExecutionEvidenceService(ExecutionRecoveryAccess access) { this.access=access; }

    /**
     * Admite reportes de la adquisición original incluso vencida. La coincidencia
     * de credenciales acredita procedencia, nunca verificación del efecto.
     */
    public Evidence report(UUID id, ExecutionActivityService.Ownership owner, Outcome outcome,
            String reference, Instant occurredAt) {
        Objects.requireNonNull(owner);
        return append(id,owner.executionId(),Source.EXECUTOR_REPORT,owner.executorId(),owner,
                outcome,reference,Objects.requireNonNull(occurredAt),null);
    }

    /**
     * Registra una observacion interna con el reloj de recepcion de la base.
     * Conserva la fecha original al deduplicar; no acredita verificacion remota.
     * Los reportes externos mantienen su fecha explicita y su validacion estricta.
     */
    Evidence reportObservation(UUID id, ExecutionActivityService.Ownership owner, Outcome outcome,
            String reference) {
        Objects.requireNonNull(owner);
        return append(id,owner.executionId(),Source.EXECUTOR_REPORT,owner.executorId(),owner,
                outcome,reference,null,null);
    }

    /**
     * Registra evidencia obtenida por un adaptador verificador confiable. Exige
     * permiso RECONCILE_ACTION vigente y responsable aprobado; no autentica al
     * destinatario remoto ni convierte un reporte del ejecutor en verificación.
     */
    public Evidence verified(UUID id,UUID executionId,UUID authorizationId,String reviewer,
            Outcome outcome,String reference,Instant occurredAt) {
        return append(id,executionId,Source.DESTINATION_VERIFIED,reviewer,null,
                outcome,reference,Objects.requireNonNull(occurredAt),authorizationId);
    }

    /**
     * Fecha una observación local dentro de su escritura. Reenvíos concurrentes
     * del mismo ID conservan el instante original bajo el bloqueo del intento.
     * Solo la ruta interna de verificación configurada utiliza esta variante.
     */
    Evidence verifiedObservation(UUID id, UUID executionId, UUID authorizationId, String reviewer, String reference) {
        return append(id,executionId,Source.DESTINATION_VERIFIED,reviewer,null,
                Outcome.EFFECT_APPLIED,reference,null,authorizationId);
    }

    /** Valida, deduplica por identidad/contenido e inserta bajo bloqueo del plan. */
    private Evidence append(UUID id,UUID executionId,Source source,String actor,ExecutionActivityService.Ownership owner,
            Outcome outcome,String reference,Instant occurredAt,UUID authorizationId) {
        Objects.requireNonNull(id); Objects.requireNonNull(outcome);
        String who=ExecutionRecoveryAccess.text(actor,255), ref=ExecutionRecoveryAccess.text(reference,500);
        return Objects.requireNonNull(access.transaction.execute(tx->{
            var attempt=access.lock(executionId);
            if(attempt.startedAt()==null) throw ExecutionRecoveryAccess.denied("El intento no comenzó");
            if(owner!=null && (!owner.executorId().equals(attempt.executor())||!Objects.equals(owner.fencingToken(),attempt.token()))) {
                throw ExecutionRecoveryAccess.denied("Reporte de otra adquisición");
            }
            if(source==Source.DESTINATION_VERIFIED) access.authorize(attempt,authorizationId,who);
            Instant now=access.activity.databaseNow().truncatedTo(ChronoUnit.MICROS);
            var existing=access.jdbc.query("SELECT * FROM execution_evidence WHERE evidence_id=?",this::map,id);
            Instant occurred=(occurredAt!=null?occurredAt:
                    existing.isEmpty()?now:existing.getFirst().occurredAt()).truncatedTo(ChronoUnit.MICROS);
            if(occurred.isAfter(now)) throw new IllegalArgumentException("La evidencia no puede ocurrir en el futuro");
            var requested=new Evidence(id,executionId,source,who,owner==null?null:owner.executorId(),
                    owner==null?null:owner.fencingToken(),outcome,ref,occurred,now,authorizationId);
            if(!existing.isEmpty()) {
                var old=existing.getFirst();
                var comparable=new Evidence(id,executionId,source,who,requested.executorId(),requested.fencingToken(),
                        outcome,ref,occurred,old.receivedAt(),authorizationId);
                if(!old.equals(comparable)) throw ExecutionRecoveryAccess.denied("ID de evidencia reutilizado con otro contenido");
                return old;
            }
            access.jdbc.update("""
                    INSERT INTO execution_evidence(evidence_id,execution_id,source_type,reported_by,executor_id,
                        fencing_token,effect_outcome,external_reference,occurred_at,received_at,verification_authorization_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,id,executionId,source.name(),who,requested.executorId(),requested.fencingToken(),outcome.name(),
                    ref,Timestamp.from(occurred),Timestamp.from(now),authorizationId);
            return requested;
        }));
    }

    /** Devuelve el historial completo, incluidos reportes contradictorios, sin actualizarlo. */
    public List<Evidence> findByAttempt(UUID executionId) {
        return List.copyOf(access.jdbc.query("SELECT * FROM execution_evidence WHERE execution_id=? ORDER BY received_at,evidence_id",
                this::map,Objects.requireNonNull(executionId)));
    }

    /** Reconstruye evidencia con sus identidades y tiempos originales. */
    private Evidence map(ResultSet rs,int row) throws SQLException {
        return new Evidence(rs.getObject("evidence_id",UUID.class),rs.getObject("execution_id",UUID.class),
                Source.valueOf(rs.getString("source_type")),rs.getString("reported_by"),rs.getString("executor_id"),
                rs.getObject("fencing_token",Long.class),Outcome.valueOf(rs.getString("effect_outcome")),
                rs.getString("external_reference"),rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("received_at").toInstant(),rs.getObject("verification_authorization_id",UUID.class));
    }
}
