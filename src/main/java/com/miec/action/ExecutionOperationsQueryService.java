package com.miec.action;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Bandeja operativa interna con cursor estable y métricas; no modifica intentos. */
@Service
public class ExecutionOperationsQueryService {
    /** ALL muestra historial; NEEDS_REVIEW excluye resoluciones vigentes y cancelaciones confirmadas. */
    public enum Filter { ALL, NEEDS_REVIEW }
    /** Posición por fecha e identidad; evita OFFSET y empates ambiguos. */
    public record Cursor(Instant createdAt,UUID executionId) {
        /** Exige ambas partes del cursor. */
        public Cursor { Objects.requireNonNull(createdAt);Objects.requireNonNull(executionId); }
    }
    /** Página inmutable; nextCursor solo existe cuando queda al menos otra fila. */
    public record Page(List<ExecutionOutcomeQueryService.View> items,Cursor nextCursor) {
        /** Impide que el consumidor altere los resultados de la consulta. */
        public Page { items=List.copyOf(items); }
    }
    /** Conteos por intento, no por acción, medidos en una transacción consistente. */
    public record Metrics(Map<ExecutionOutcomeQueryService.EffectiveOutcome,Long> byOutcome,long needsReview,Instant measuredAt) {
        /** Conserva una instantánea inmutable de las métricas. */
        public Metrics { byOutcome=Map.copyOf(byOutcome); }
    }
    private final JdbcTemplate jdbc;
    private final ExecutionActivityService activity;
    private final TransactionTemplate reads;
    private static final String REVIEW="""
            (q.conflicting OR
             (q.resolution IS NULL AND q.pending_review_id IS NULL AND
              (q.execution_status='UNKNOWN' OR
               (q.execution_status='IN_PROGRESS' AND (q.lease_expires_at IS NULL OR q.lease_expires_at<=?)) OR
               (q.execution_status='PENDING' AND q.created_at<=?))))
            """;
    private static final String EFFECTIVE="""
            CASE WHEN q.conflicting THEN 'CONFLICT'
             WHEN q.pending_review_id IS NOT NULL THEN 'CANCELLED_PENDING'
             WHEN q.resolution='APPLIED' THEN 'APPLIED'
             WHEN q.resolution='NO_EFFECT' THEN 'NO_EFFECT'
             WHEN q.execution_status='PENDING' THEN 'PENDING'
             WHEN q.execution_status='IN_PROGRESS' THEN
                CASE WHEN q.lease_expires_at>? THEN 'RUNNING' ELSE 'EXPIRED' END
             WHEN q.execution_status='COMPLETED' THEN 'APPLIED'
             WHEN q.execution_status='FAILED' THEN
                CASE WHEN q.effect_outcome='NO_EFFECT_CONFIRMED' THEN 'NO_EFFECT' ELSE 'APPLIED' END
             ELSE 'UNKNOWN' END
            """;

    /** Configura lecturas independientes con aislamiento repetible para los conteos agrupados. */
    public ExecutionOperationsQueryService(JdbcTemplate jdbc,ExecutionActivityService activity,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.activity=activity;this.reads=new TransactionTemplate(manager);
        reads.setReadOnly(true);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setTimeout(10);
    }

    /**
     * Devuelve como máximo cien filas, usando una fila adicional para detectar
     * continuación. planId nulo consulta todos los planes para operación interna.
     * La paginación es viva: los cambios posteriores pueden alterar la pertenencia
     * a NEEDS_REVIEW, pero las identidades ya recorridas no se repiten.
     */
    public Page page(UUID planId,Filter filter,Cursor after,int limit,Duration pendingMinimumAge) {
        Objects.requireNonNull(filter);validateAge(pendingMinimumAge);
        if(limit<1||limit>100) throw new IllegalArgumentException("Tamaño de página entre 1 y 100");
        return Objects.requireNonNull(reads.execute(tx->{
            Instant now=activity.databaseNow();var args=new ArrayList<Object>();
            String sql="SELECT q.* FROM ("+ExecutionOutcomeQueryService.PROJECTION+") q WHERE 1=1";
            if(planId!=null) {sql+=" AND q.plan_id=?";args.add(planId);}
            if(filter==Filter.NEEDS_REVIEW) {sql+=" AND "+REVIEW;args.add(Timestamp.from(now));args.add(Timestamp.from(now.minus(pendingMinimumAge)));}
            if(after!=null) {
                sql+=" AND (q.created_at>? OR (q.created_at=? AND q.execution_id>?))";
                args.add(Timestamp.from(after.createdAt()));args.add(Timestamp.from(after.createdAt()));args.add(after.executionId());
            }
            sql+=" ORDER BY q.created_at,q.execution_id LIMIT ?";args.add(limit+1);
            var found=jdbc.query(sql,(rs,row)->ExecutionOutcomeQueryService.map(rs,now),args.toArray());
            boolean more=found.size()>limit;
            var items=found.subList(0,Math.min(limit,found.size()));
            var last=items.isEmpty()?null:items.getLast();
            return new Page(items,more?new Cursor(last.createdAt(),last.executionId()):null);
        }));
    }

    /** Agrupa todo el volumen en SQL y cuenta revisión con el mismo snapshot y referencia temporal. */
    public Metrics metrics(UUID planId,Duration pendingMinimumAge) {
        validateAge(pendingMinimumAge);
        return Objects.requireNonNull(reads.execute(tx->{
            Instant now=activity.databaseNow();
            var args=new ArrayList<Object>();args.add(Timestamp.from(now));
            String where=planId==null?"":" WHERE q.plan_id=?";
            if(planId!=null) args.add(planId);
            String sql="SELECT effective,COUNT(*) AS n FROM (SELECT "+EFFECTIVE+" AS effective FROM ("+
                    ExecutionOutcomeQueryService.PROJECTION+") q"+where+") grouped GROUP BY effective";
            var counts=new EnumMap<ExecutionOutcomeQueryService.EffectiveOutcome,Long>(ExecutionOutcomeQueryService.EffectiveOutcome.class);
            for(var state:ExecutionOutcomeQueryService.EffectiveOutcome.values()) counts.put(state,0L);
            jdbc.query(sql,(org.springframework.jdbc.core.RowCallbackHandler)rs->counts.put(
                    ExecutionOutcomeQueryService.EffectiveOutcome.valueOf(rs.getString("effective")),rs.getLong("n")),args.toArray());
            args.clear();args.add(Timestamp.from(now));args.add(Timestamp.from(now.minus(pendingMinimumAge)));
            String reviewSql="SELECT COUNT(*) FROM ("+ExecutionOutcomeQueryService.PROJECTION+") q WHERE "+REVIEW;
            if(planId!=null) {reviewSql+=" AND q.plan_id=?";args.add(planId);}
            long pending=Objects.requireNonNull(jdbc.queryForObject(reviewSql,Long.class,args.toArray()));
            return new Metrics(counts,pending,now);
        }));
    }

    /** Exige una antigüedad positiva incluso para consultas que no filtran por pendientes. */
    private static void validateAge(Duration minimumAge) {
        Objects.requireNonNull(minimumAge);
        if(minimumAge.isNegative()||minimumAge.isZero()) throw new IllegalArgumentException("Antigüedad positiva requerida");
    }
}
