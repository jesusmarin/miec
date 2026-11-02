package com.miec.action;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Escenarios independientes para las pruebas de evidencia y reconciliación. */
public final class ExecutionRecoveryTestSupport {
    /** Referencias confirmadas y autorizaciones separadas para ejecutar y revisar. */
    public record Fixture(UUID plan, UUID cycle, UUID action, UUID executeAuthorization, UUID reviewAuthorization) {
        /** Construye el comando del servicio de intentos. */
        ExecutionAttemptService.CreateAttemptCommand attemptCommand() {
            return new ExecutionAttemptService.CreateAttemptCommand(action,plan,cycle,executeAuthorization,"PROCESS_EXECUTION","worker");
        }
        /** Construye el comando del coordinador. */
        public AuthorizedActionExecutionService.ExecutionCommand command() {
            return new AuthorizedActionExecutionService.ExecutionCommand(action,plan,cycle,executeAuthorization,"PROCESS_EXECUTION","worker");
        }
    }

    /** Crea datos con UUID nuevos, visibles a transacciones REQUIRES_NEW. */
    public static Fixture fixture(JdbcTemplate jdbc) {
        UUID system=UUID.randomUUID(), cycle=UUID.randomUUID(), plan=UUID.randomUUID(), action=UUID.randomUUID();
        UUID execute=UUID.randomUUID(), review=UUID.randomUUID();
        jdbc.update("INSERT INTO system_objective(system_id,name) VALUES (?,'recovery')",system);
        jdbc.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
        jdbc.update("INSERT INTO intervention_plan(plan_id,cycle_id,status,actions) VALUES (?,?,'PROPOSED',? FORMAT JSON)",plan,cycle,"""
                {"schemaVersion":"01","actions":[{"actionId":"%s","actionType":"PROCESS_EXECUTION"}]}
                """.formatted(action));
        for (boolean execution : new boolean[]{true,false}) {
            jdbc.update("""
                    INSERT INTO authorizations(authorization_id,plan_id,action_id,requested_by,approved_by,
                        scope,status,issued_at,expires_at) VALUES (?,?,?,'requester',?,?,'APPROVED',?,?)
                    """,execution?execute:review,plan,action,execution?"approver":"reviewer",
                    execution?"EXECUTE_ACTION":"RECONCILE_ACTION",
                    Timestamp.from(Instant.now().minusSeconds(60)),Timestamp.from(Instant.now().plusSeconds(3600)));
        }
        return new Fixture(plan,cycle,action,execute,review);
    }

    /** Consulta el reloj interno para verificadores experimentales sin ampliar la API de producción. */
    public static Instant databaseNow(ExecutionActivityService activity) {
        return activity.databaseNow();
    }

    /** Simula un lease vencido sin cambiar identidad ni generación del propietario. */
    static void expire(JdbcTemplate jdbc, UUID executionId) {
        jdbc.update("""
                UPDATE execution_attempt SET started_at=DATEADD('SECOND',-60,CURRENT_TIMESTAMP),
                    heartbeat_at=DATEADD('SECOND',-60,CURRENT_TIMESTAMP),
                    lease_expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE execution_id=?
                """,executionId);
    }
}
