package com.miec.action;

import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Revisión conservadora y manual de trabajo que nunca adquirió propiedad. */
@Service
public class PendingExecutionReviewService {
    /** Cancelación auditada anterior al inicio; el intento original permanece intacto. */
    public record Review(UUID id,UUID executionId,UUID authorizationId,String reviewer,String rationale,
                         Instant originalCreatedAt,Instant reviewedAt) { }
    private final ExecutionRecoveryAccess access;

    /** Comparte el orden de bloqueos plan/intento/autorización con el servicio de ejecución. */
    public PendingExecutionReviewService(ExecutionRecoveryAccess access) { this.access=access; }

    /**
     * Cancela un PENDING antiguo mediante una decisión independiente. El inicio
     * consulta esta decisión bajo el mismo bloqueo; nunca puede ejecutarse después.
     * Una repetición idéntica devuelve la revisión anterior.
     */
    public Review cancelBeforeStart(UUID executionId,UUID authorizationId,String reviewer,String rationale,Duration minimumAge) {
        Objects.requireNonNull(minimumAge);
        if(minimumAge.isZero()||minimumAge.isNegative()) throw new IllegalArgumentException("Antigüedad positiva requerida");
        String who=ExecutionRecoveryAccess.text(reviewer,255),why=ExecutionRecoveryAccess.text(rationale,1000);
        return Objects.requireNonNull(access.transaction.execute(tx->{
            var attempt=access.lock(executionId);access.authorize(attempt,authorizationId,who);
            if(!"PENDING".equals(attempt.status())||attempt.startedAt()!=null||attempt.executor()!=null) {
                throw ExecutionRecoveryAccess.denied("Solo trabajo nunca iniciado puede cancelarse por esta vía");
            }
            var existing=find(executionId);
            if(existing.isPresent()) {
                var old=existing.get();
                if(!old.authorizationId().equals(authorizationId)||!old.reviewer().equals(who)||!old.rationale().equals(why)) {
                    throw ExecutionRecoveryAccess.denied("El pendiente ya tiene otra revisión");
                }
                return old;
            }
            Instant created=Objects.requireNonNull(access.jdbc.queryForObject("SELECT created_at FROM execution_attempt WHERE execution_id=?",
                    (rs,row)->rs.getTimestamp(1).toInstant(),executionId));
            Instant now=access.activity.databaseNow().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(created.isAfter(now.minus(minimumAge))) throw ExecutionRecoveryAccess.denied("El pendiente todavía es reciente");
            UUID id=UUID.randomUUID();
            access.jdbc.update("""
                    INSERT INTO execution_pending_review(review_id,execution_id,authorization_id,reviewed_by,
                        rationale,original_created_at,reviewed_at) VALUES (?,?,?,?,?,?,?)
                    """,id,executionId,authorizationId,who,why,Timestamp.from(created),Timestamp.from(now));
            return new Review(id,executionId,authorizationId,who,why,created,now);
        }));
    }

    /** Consulta una revisión sin actualizar ni eliminar el intento. */
    public Optional<Review> find(UUID executionId) {
        return access.jdbc.query("SELECT * FROM execution_pending_review WHERE execution_id=?",(rs,row)->new Review(
                rs.getObject("review_id",UUID.class),executionId,rs.getObject("authorization_id",UUID.class),rs.getString("reviewed_by"),
                rs.getString("rationale"),rs.getTimestamp("original_created_at").toInstant(),rs.getTimestamp("reviewed_at").toInstant()),
                Objects.requireNonNull(executionId)).stream().findFirst();
    }

    /** Detecta pendientes antiguos sin cancelarlos; las revisiones ya confirmadas quedan excluidas. */
    public List<UUID> findCandidates(Duration minimumAge,int limit) {
        Objects.requireNonNull(minimumAge);
        if(minimumAge.isZero()||minimumAge.isNegative()||limit<1||limit>1000) throw new IllegalArgumentException("Límites inválidos");
        return List.copyOf(access.jdbc.query("""
                SELECT a.execution_id FROM execution_attempt a
                WHERE a.execution_status='PENDING' AND a.created_at<=?
                  AND NOT EXISTS(SELECT 1 FROM execution_pending_review r WHERE r.execution_id=a.execution_id)
                ORDER BY a.created_at,a.execution_id LIMIT ?
                """,(rs,row)->rs.getObject(1,UUID.class),Timestamp.from(access.activity.databaseNow().minus(minimumAge)),limit));
    }
}
