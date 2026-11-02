package com.miec.action;

import com.miec.authorization.AuthorizationPersistenceService;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Infraestructura interna compartida; conserva el orden de bloqueos de ejecución. */
@Component
class ExecutionRecoveryAccess {
    final JdbcTemplate jdbc;
    final ExecutionActivityService activity;
    final TransactionTemplate transaction;
    private final AuthorizationPersistenceService authorizations;

    /** Configura transacciones independientes para evidencia y decisiones. */
    ExecutionRecoveryAccess(JdbcTemplate jdbc, ExecutionActivityService activity,
            PlatformTransactionManager manager, AuthorizationPersistenceService authorizations) {
        this.jdbc=jdbc; this.activity=activity; this.authorizations=authorizations;
        this.transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    /** Vista bloqueada necesaria para comprobar procedencia y elegibilidad. */
    record Attempt(UUID id, UUID plan, UUID action, String status, Instant lease,
                   String executor, Long token, Instant startedAt) { }

    /** Bloquea plan y luego intento; todas las escrituras de recuperación usan este orden. */
    Attempt lock(UUID id) {
        Objects.requireNonNull(id);
        var plans=jdbc.query("SELECT plan_id FROM execution_attempt WHERE execution_id=?",
                (rs,row)->rs.getObject(1,UUID.class),id);
        if(plans.isEmpty()) throw denied("Intento inexistente");
        jdbc.queryForObject("SELECT plan_id FROM intervention_plan WHERE plan_id=? FOR UPDATE",UUID.class,plans.getFirst());
        return jdbc.queryForObject("""
                SELECT execution_id,plan_id,planned_action_id,execution_status,lease_expires_at,
                    executor_id,fencing_token,started_at FROM execution_attempt WHERE execution_id=? FOR UPDATE
                """,(rs,row)->new Attempt(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getString(4),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant(),
                rs.getString(6),rs.getObject(7,Long.class),rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toInstant()),id);
    }

    /** Revalida permiso y atribuye la operación al responsable aprobado bajo bloqueo. */
    void authorize(Attempt attempt, UUID authorizationId, String actor) {
        Objects.requireNonNull(authorizationId);
        var approvers=jdbc.query("SELECT approved_by FROM authorizations WHERE authorization_id=? FOR UPDATE",
                (rs,row)->rs.getString(1),authorizationId);
        if(approvers.isEmpty() || !actor.equals(approvers.getFirst()) || !authorizations.evaluate(authorizationId,
                new AuthorizationUseRequest(attempt.plan(),attempt.action(),"RECONCILE_ACTION",activity.databaseNow())).allowed()) {
            throw denied("Responsable o autorización de reconciliación inválidos");
        }
    }

    /** Valida texto obligatorio y conserva una representación normalizada. */
    static String text(String value,int max) {
        Objects.requireNonNull(value);
        String result=value.trim();
        if(result.isEmpty()||result.length()>max) throw new IllegalArgumentException("Texto inválido");
        return result;
    }

    /** Crea el rechazo de dominio común a los servicios de recuperación. */
    static ExecutionAttemptService.AttemptDeniedException denied(String message) {
        return new ExecutionAttemptService.AttemptDeniedException("IR-009: "+message);
    }
}
