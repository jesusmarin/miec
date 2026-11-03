package com.miec.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Incidentes append-only por aplicación. Nunca almacena mensajes de Throwable,
 * SQL, cuerpos, secretos o trazas. Fallar al auditar no habilita ejecución ni retry.
 */
@Service
public class ExecutionIncidentService {
    private static final Logger LOG=LoggerFactory.getLogger(ExecutionIncidentService.class);
    /** Fase conocida del coordinador; un rechazo local no certifica ausencia global del efecto. */
    public enum Phase { REJECTED_BEFORE_SEND, OUTCOME_UNCERTAIN, APPLIED_CONFIRMATION_FAILED, COORDINATION_INTERRUPTED }
    /** Frontera que registra el hecho; COORDINATOR puede referenciar el incidente CORE propagado. */
    public enum Origin { CORE, COORDINATOR }
    /** DTO público sin causas internas; el contador es una fotografía de secundarios al registrar. */
    public record Incident(UUID incidentId,UUID relatedIncidentId,UUID runId,UUID executionId,UUID planId,UUID cycleId,UUID actionId,
                           Origin origin,Phase phase,String code,String recordedBy,int secondaryFailureCount,Instant occurredAt) { }
    private final JdbcTemplate jdbc;
    private final ExecutionActivityService activity;
    private final ExecutionOutcomeQueryService outcomes;
    private final TransactionTemplate transaction;

    /** Usa una transacción independiente para no perder el incidente al fallar la coordinación. */
    public ExecutionIncidentService(JdbcTemplate jdbc,ExecutionActivityService activity,ExecutionOutcomeQueryService outcomes,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.activity=activity;this.outcomes=outcomes;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(10);
    }

    /**
     * Registra desde el núcleo sin sustituir su excepción original. admissionCode
     * procede de la frontera interna y no del tipo de excepción aportado por un adaptador.
     */
    void captureCore(UUID executionId,Phase phase,ExecutionDispatchRejectedException.Code admissionCode,Throwable original) {
        UUID id=UUID.randomUUID();
        String code=phase==Phase.REJECTED_BEFORE_SEND?(admissionCode==null?"ATTEMPT_START_REJECTED":admissionCode.name()):
                phase==Phase.APPLIED_CONFIRMATION_FAILED?"APPLIED_CONFIRMATION_FAILED":"REMOTE_OUTCOME_UNCERTAIN";
        try {
            transaction.executeWithoutResult(status->{
                var scope=jdbc.queryForObject("SELECT plan_id,cycle_id,planned_action_id,actor_id FROM execution_attempt WHERE execution_id=?",
                        (rs,row)->new Scope(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4)),executionId);
                var runs=jdbc.query("SELECT r.run_id FROM plan_execution r JOIN plan_execution_step s ON s.run_id=r.run_id WHERE r.plan_id=? AND s.action_id=?",
                        (rs,row)->rs.getObject(1,UUID.class),scope.plan(),scope.action());
                save(new Incident(id,null,runs.isEmpty()?null:runs.getFirst(),executionId,scope.plan(),scope.cycle(),scope.action(),Origin.CORE,phase,code,scope.actor(),original.getSuppressed().length,now()));
            });
            log(id,Origin.CORE,phase,code,original,false);
        } catch(RuntimeException auditFailure) {
            secondary(original,new ExecutionIncidentAuditException(id,auditFailure));log(id,Origin.CORE,phase,code,original,true);
        }
    }

    /**
     * Conserva la propagación hacia el run con una referencia al incidente del núcleo.
     * Si no existe, diferencia un commit aplicado de una interrupción de coordinación.
     * Una auditoría fallida provoca una excepción saneada con la causa original intacta.
     */
    public UUID captureCoordination(UUID runId,String actor,Throwable original) {
        UUID id=UUID.randomUUID();
        try {
            var incident=transaction.execute(status->{
                var scopes=jdbc.query("""
                        SELECT r.plan_id,r.cycle_id,s.action_id,s.expected_operation_key,s.step_state
                        FROM plan_execution r JOIN plan_execution_step s ON s.run_id=r.run_id WHERE r.run_id=?
                        ORDER BY CASE WHEN s.step_state='APPLIED' THEN 1 ELSE 0 END,
                            CASE WHEN s.step_state='APPLIED' THEN -s.ordinal ELSE s.ordinal END
                        """,(rs,row)->new CoordinationScope(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getString(5)),runId);
                if(scopes.isEmpty()) throw new IllegalStateException("Coordinación sin contexto auditable");
                var scope=scopes.getFirst(); var fact=outcomes.findLatest(scope.plan(),scope.action());
                UUID execution=fact.map(ExecutionOutcomeQueryService.View::executionId).orElse(null);
                var core=execution==null?List.<Incident>of():jdbc.query("SELECT * FROM execution_incident WHERE execution_id=? AND origin='CORE' ORDER BY occurred_at DESC,incident_id DESC LIMIT 1",this::map,execution);
                var related=core.isEmpty()?null:core.getFirst();
                boolean applied=fact.isPresent()&&scope.operation().equals(fact.get().operationKey())&&fact.get().outcome()==ExecutionOutcomeQueryService.EffectiveOutcome.APPLIED;
                boolean notDispatched=fact.isEmpty()&&"READY".equals(scope.stepState());
                var phase=related!=null?related.phase():applied?Phase.APPLIED_CONFIRMATION_FAILED:notDispatched?Phase.REJECTED_BEFORE_SEND:Phase.COORDINATION_INTERRUPTED;
                String code=related!=null?related.code():applied?"PLAN_PROGRESS_NOT_RECORDED":notDispatched?"PLAN_ADMISSION_REJECTED":"PLAN_COORDINATION_INTERRUPTED";
                var result=new Incident(id,related==null?null:related.incidentId(),runId,execution,scope.plan(),scope.cycle(),scope.action(),Origin.COORDINATOR,phase,code,actor,original.getSuppressed().length,now());
                save(result);return result;
            });
            log(id,Origin.COORDINATOR,incident.phase(),incident.code(),original,false);return id;
        } catch(RuntimeException auditFailure) {
            secondary(original,auditFailure);
            log(id,Origin.COORDINATOR,Phase.COORDINATION_INTERRUPTED,"INCIDENT_PERSISTENCE_UNAVAILABLE",original,true);
            throw new ExecutionIncidentAuditException(id,original);
        }
    }

    /** Lectura sin modificaciones ni permiso de ejecución vigente; la fachada autentica al lector. */
    public List<Incident> findForRun(UUID runId) {
        return List.copyOf(jdbc.query("SELECT * FROM execution_incident WHERE run_id=? ORDER BY occurred_at,incident_id",this::map,runId));
    }

    /** Contexto reconstruido del intento, nunca de IDs libres recibidos por un cliente. */
    private record Scope(UUID plan,UUID cycle,UUID action,String actor) { }
    /** Contexto del paso y clave lógica que debe corresponder a cualquier resultado observado. */
    private record CoordinationScope(UUID plan,UUID cycle,UUID action,UUID operation,String stepState) { }
    /** Solo inserta; no existe API de actualización o borrado de incidentes. */
    private void save(Incident event) {
        jdbc.update("""
                INSERT INTO execution_incident(incident_id,related_incident_id,run_id,execution_id,plan_id,cycle_id,action_id,origin,phase,code,recorded_by,secondary_failure_count,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,event.incidentId(),event.relatedIncidentId(),event.runId(),event.executionId(),event.planId(),event.cycleId(),event.actionId(),event.origin().name(),event.phase().name(),event.code(),event.recordedBy(),event.secondaryFailureCount(),Timestamp.from(event.occurredAt()));
    }
    /** Tiempo de recepción de base, con la misma precisión que TIMESTAMP(6). */
    private Instant now() { return activity.databaseNow().truncatedTo(ChronoUnit.MICROS); }
    /** Preserva el secundario sin auto-supresión cuando una prueba o proveedor reutiliza la misma excepción. */
    private static void secondary(Throwable original,Throwable failure) { if(original!=failure) original.addSuppressed(failure); }
    /** Log estructurado de fallback; no se pasa Throwable ni getMessage al logger. */
    private static void log(UUID id,Origin origin,Phase phase,String code,Throwable original,boolean unavailable) {
        LOG.warn("MIEC incident={} origin={} phase={} code={} auditUnavailable={} secondaryFailures={}",id,origin,phase,code,unavailable,original.getSuppressed().length);
    }
    /** Reconstruye únicamente los metadatos públicos saneados. */
    private Incident map(ResultSet rs,int row) throws SQLException {
        return new Incident(rs.getObject("incident_id",UUID.class),rs.getObject("related_incident_id",UUID.class),rs.getObject("run_id",UUID.class),rs.getObject("execution_id",UUID.class),
                rs.getObject("plan_id",UUID.class),rs.getObject("cycle_id",UUID.class),rs.getObject("action_id",UUID.class),Origin.valueOf(rs.getString("origin")),
                Phase.valueOf(rs.getString("phase")),rs.getString("code"),rs.getString("recorded_by"),rs.getInt("secondary_failure_count"),rs.getTimestamp("occurred_at").toInstant());
    }
}
