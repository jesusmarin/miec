package com.miec.action;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Proyección de lectura: combina ejecución y reconciliación sin reescribir su historia. */
@Service
public class ExecutionOutcomeQueryService {
    /** Resultado operativo derivado; no son estados nuevos de execution_attempt. */
    public enum EffectiveOutcome { PENDING, CANCELLED_PENDING, RUNNING, EXPIRED, UNKNOWN, APPLIED, NO_EFFECT, CONFLICT }
    /** Vista de un intento que conserva explícitamente el estado original y su resolución. */
    public record View(UUID executionId,UUID planId,UUID actionId,UUID operationKey,int attemptNumber,
            String originalStatus,EffectiveOutcome outcome,String resolution,String resultReference,
            Instant createdAt,Instant leaseExpiresAt) { }
    private final JdbcTemplate jdbc;
    private final ExecutionActivityService activity;

    /** Configura consultas SQL y la misma referencia temporal que controla los leases. */
    public ExecutionOutcomeQueryService(JdbcTemplate jdbc,ExecutionActivityService activity) {
        this.jdbc=jdbc;this.activity=activity;
    }

    /** Proyección atómica: no consulta evidencias por separado ni mezcla snapshots de distintas consultas. */
    static final String PROJECTION="""
            SELECT a.*,r.resolution,proof.external_reference AS verified_reference,c.review_id AS pending_review_id,
                ((a.effect_outcome='EFFECT_APPLIED' OR EXISTS(SELECT 1 FROM execution_evidence e WHERE e.execution_id=a.execution_id AND e.effect_outcome='EFFECT_APPLIED'))
                 AND (a.effect_outcome='NO_EFFECT_CONFIRMED' OR EXISTS(SELECT 1 FROM execution_evidence e WHERE e.execution_id=a.execution_id AND e.effect_outcome='NO_EFFECT_CONFIRMED'))) AS conflicting
            FROM execution_attempt a
            LEFT JOIN execution_reconciliation r ON r.execution_id=a.execution_id
            LEFT JOIN execution_evidence proof ON proof.evidence_id=r.evidence_id
            LEFT JOIN execution_pending_review c ON c.execution_id=a.execution_id
            """;

    /** Consulta un intento específico sin alterar decisiones ni propiedad. */
    public Optional<View> findAttempt(UUID executionId) {
        Instant now=activity.databaseNow();
        return jdbc.query(PROJECTION+" WHERE a.execution_id=?",(rs,row)->map(rs,now),
                Objects.requireNonNull(executionId)).stream().findFirst();
    }

    /** Consulta el intento más reciente de la acción, sin recuperar un resultado anterior al último intento. */
    public Optional<View> findLatest(UUID planId,UUID actionId) {
        Instant now=activity.databaseNow();
        return jdbc.query(PROJECTION+" WHERE a.plan_id=? AND a.planned_action_id=? ORDER BY a.attempt_number DESC LIMIT 1",
                (rs,row)->map(rs,now),Objects.requireNonNull(planId),Objects.requireNonNull(actionId)).stream().findFirst();
    }

    /** Deriva un resultado conservador a partir de una sola fila y sus evidencias agregadas. */
    static View map(ResultSet rs,Instant now) throws SQLException {
        String original=rs.getString("execution_status"),resolution=rs.getString("resolution");
        var lease=rs.getTimestamp("lease_expires_at");
        EffectiveOutcome effective;
        if(rs.getBoolean("conflicting")) effective=EffectiveOutcome.CONFLICT;
        else if(rs.getObject("pending_review_id")!=null) effective=EffectiveOutcome.CANCELLED_PENDING;
        else if("APPLIED".equals(resolution)) effective=EffectiveOutcome.APPLIED;
        else if("NO_EFFECT".equals(resolution)) effective=EffectiveOutcome.NO_EFFECT;
        else effective=switch(original) {
            case "PENDING" -> EffectiveOutcome.PENDING;
            case "IN_PROGRESS" -> lease!=null&&lease.toInstant().isAfter(now)?EffectiveOutcome.RUNNING:EffectiveOutcome.EXPIRED;
            case "COMPLETED" -> EffectiveOutcome.APPLIED;
            case "FAILED" -> "NO_EFFECT_CONFIRMED".equals(rs.getString("effect_outcome"))?EffectiveOutcome.NO_EFFECT:EffectiveOutcome.APPLIED;
            default -> EffectiveOutcome.UNKNOWN;
        };
        String reference="APPLIED".equals(resolution)?rs.getString("verified_reference"):rs.getString("result_reference");
        if(effective!=EffectiveOutcome.APPLIED) reference=null;
        return new View(rs.getObject("execution_id",UUID.class),rs.getObject("plan_id",UUID.class),
                rs.getObject("planned_action_id",UUID.class),rs.getObject("operation_key",UUID.class),rs.getInt("attempt_number"),
                original,effective,resolution,reference,rs.getTimestamp("created_at").toInstant(),lease==null?null:lease.toInstant());
    }
}
