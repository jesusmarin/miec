package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas reales de propiedad, vigencia y serialización sin transacción exterior. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:activity_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class ExecutionActivityServiceTest {
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionActivityService activity;
    @Autowired private JdbcTemplate jdbc;

    /** Crea referencias confirmadas y un intento autorizado todavía PENDING. */
    private ExecutionAttemptService.AttemptSnapshot pending() {
        UUID system=UUID.randomUUID(), cycle=UUID.randomUUID(), plan=UUID.randomUUID();
        UUID action=UUID.randomUUID(), auth=UUID.randomUUID();
        jdbc.update("INSERT INTO system_objective(system_id,name) VALUES (?,'activity')",system);
        jdbc.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
        jdbc.update("INSERT INTO intervention_plan(plan_id,cycle_id,status,actions) VALUES (?,?,'PROPOSED',? FORMAT JSON)",
                plan,cycle,"""
                {"schemaVersion":"01","actions":[{"actionId":"%s","actionType":"PROCESS_EXECUTION"}]}
                """.formatted(action));
        jdbc.update("""
                INSERT INTO authorizations(authorization_id,plan_id,action_id,requested_by,
                    approved_by,scope,status,issued_at,expires_at)
                VALUES (?,?,?,'requester','approver','EXECUTE_ACTION','APPROVED',?,?)
                """,auth,plan,action,Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        return attempts.createAttempt(new ExecutionAttemptService.CreateAttemptCommand(
                action,plan,cycle,auth,"PROCESS_EXECUTION","test"));
    }

    /** Inicia mediante el único camino autorizado y devuelve las credenciales reales. */
    private ExecutionAttemptService.AttemptSnapshot started() {
        return attempts.startAuthorizedAttempt(pending().executionId(),"PROCESS_EXECUTION");
    }

    /** Comprueba adquisición completa, token creciente y renovación visible. */
    @Test @DisplayName("T01-T02: adquisición atómica y heartbeat del propietario")
    void shouldAcquireAndRenew() {
        var p=pending();
        assertNull(p.executorId());
        var first=attempts.startAuthorizedAttempt(p.executionId(),"PROCESS_EXECUTION");
        assertEquals(activity.executorId(),first.executorId());
        assertEquals(first.startedAt(),first.heartbeatAt());
        assertTrue(first.leaseExpiresAt().isAfter(first.heartbeatAt()));
        activity.heartbeat(first.ownership());
        var after=attempts.findHistory(first.planId(),first.plannedActionId()).getFirst();
        assertEquals(first.version()+1,after.version());
        assertFalse(after.heartbeatAt().isBefore(first.heartbeatAt()));
        assertTrue(started().fencingToken()>first.fencingToken());
    }

    /** Prueba propietario y generación incorrectos para renovar y finalizar. */
    @ParameterizedTest @ValueSource(strings={"OWNER","TOKEN"})
    @DisplayName("T03-T04: rechaza propietario o token incorrecto sin escrituras")
    void shouldRejectWrongCredentials(String kind) {
        var s=started();
        var wrong=new ExecutionActivityService.Ownership(s.executionId(),
                kind.equals("OWNER")?UUID.randomUUID().toString():s.executorId(),
                kind.equals("TOKEN")?s.fencingToken()+1:s.fencingToken());
        var before=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->activity.heartbeat(wrong));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->attempts.completeAttempt(wrong,null));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->attempts.markUnknown(wrong,"late"));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->attempts.failAttempt(wrong,
                ExecutionRetryPolicy.EffectOutcome.NO_EFFECT_CONFIRMED,"late"));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId()));
    }

    /** Simula vencimiento sin pausas; renovar o cerrar no puede resucitar propiedad. */
    @Test @DisplayName("T05-T10: lease vencido rechaza renovación, finalización y reintento")
    void shouldRejectExpiredOwnership() {
        var s=started();
        jdbc.update("""
                UPDATE execution_attempt SET started_at=DATEADD('SECOND',-60,CURRENT_TIMESTAMP),
                    heartbeat_at=DATEADD('SECOND',-60,CURRENT_TIMESTAMP),
                    lease_expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE execution_id=?
                """,s.executionId());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->activity.heartbeat(s.ownership()));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->attempts.completeAttempt(s.ownership(),null));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->attempts.createAttempt(
                new ExecutionAttemptService.CreateAttemptCommand(s.plannedActionId(),s.planId(),s.cycleId(),
                        s.authorizationId(),"PROCESS_EXECUTION","test")));
    }

    /** Cada estado terminal conserva credenciales y rechaza posteriores heartbeats. */
    @ParameterizedTest @ValueSource(strings={"COMPLETED","FAILED","UNKNOWN"})
    @DisplayName("T06: los estados terminales conservan trazabilidad y no se renuevan")
    void shouldRejectTerminalRenewal(String state) {
        var s=started();
        var finished=switch(state) {
            case "COMPLETED" -> attempts.completeAttempt(s.ownership(),null);
            case "FAILED" -> attempts.failAttempt(s.ownership(),ExecutionRetryPolicy.EffectOutcome.NO_EFFECT_CONFIRMED,"test");
            default -> attempts.markUnknown(s.ownership(),"test");
        };
        assertEquals(s.ownership(),finished.ownership());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->activity.heartbeat(s.ownership()));
    }

    /** El detector distingue activos, vencidos exactos y heredados sin cambiar filas. */
    @Test @DisplayName("T07-T08: diagnóstico de actividad usa vigencia y conserva intentos")
    void shouldDiagnoseActivityWithoutModifyingAttempts() {
        var s=started();
        // Una ejecución larga con lease válido sigue ACTIVA.
        jdbc.update("UPDATE execution_attempt SET started_at=DATEADD('HOUR',-2,CURRENT_TIMESTAMP) WHERE execution_id=?",
                s.executionId());
        var fixed = new InterruptedExecutionDetectionService(jdbc,java.time.Clock.fixed(
                s.heartbeatAt(),java.time.ZoneOffset.UTC));
        assertTrue(fixed.findCandidates(java.time.Duration.ofMinutes(30),100).isEmpty());
        assertEquals(InterruptedExecutionDetectionService.ActivityClassification.ACTIVA,
                fixed.diagnose(100).stream().filter(d->d.executionId().equals(s.executionId())).findFirst().orElseThrow().classification());
        var before=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId());
        var expired=new InterruptedExecutionDetectionService(jdbc,java.time.Clock.fixed(
                s.leaseExpiresAt(),java.time.ZoneOffset.UTC));
        assertTrue(expired.findCandidates(java.time.Duration.ofMinutes(30),100).stream()
                .anyMatch(c->c.executionId().equals(s.executionId())));
        assertEquals(InterruptedExecutionDetectionService.ActivityClassification.REVISION,
                expired.diagnose(100).stream().filter(d->d.executionId().equals(s.executionId())).findFirst().orElseThrow().classification());
        assertEquals(before,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId()));
        jdbc.update("UPDATE execution_attempt SET executor_id=NULL,fencing_token=NULL,heartbeat_at=NULL,lease_expires_at=NULL WHERE execution_id=?",s.executionId());
        assertEquals(InterruptedExecutionDetectionService.ActivityClassification.INDETERMINADA,
                expired.diagnose(100).stream().filter(d->d.executionId().equals(s.executionId())).findFirst().orElseThrow().classification());
    }

    /** Dos renovaciones simultáneas se serializan y aumentan la versión dos veces. */
    @Test @DisplayName("T09: dos heartbeats concurrentes conservan integridad")
    void shouldSerializeConcurrentRenewals() throws Exception {
        var s=started();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<Instant> task=()-> { barrier.await(5,TimeUnit.SECONDS); return activity.heartbeat(s.ownership()); };
            var a=pool.submit(task); var b=pool.submit(task);
            a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        }
        var after=attempts.findHistory(s.planId(),s.plannedActionId()).getFirst();
        assertEquals(s.version()+2,after.version());
        assertFalse(after.heartbeatAt().isBefore(s.heartbeatAt()));
    }

    /** Un cierre compitiendo con heartbeat nunca deja un estado terminal renovable. */
    @Test @DisplayName("Heartbeat concurrente con cierre no reabre COMPLETED")
    void shouldSerializeRenewalAndCompletion() throws Exception {
        var s=started();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            var heartbeat=pool.submit(()-> {
                barrier.await(5,TimeUnit.SECONDS);
                try { activity.heartbeat(s.ownership()); }
                catch(ExecutionAttemptService.AttemptDeniedException expected) { /* El cierre ganó. */ }
                return null;
            });
            var finish=pool.submit(()-> { barrier.await(5,TimeUnit.SECONDS); return attempts.completeAttempt(s.ownership(),null); });
            heartbeat.get(10,TimeUnit.SECONDS);
            assertEquals(ExecutionRetryPolicy.ExecutionStatus.COMPLETED,finish.get(10,TimeUnit.SECONDS).status());
        }
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->activity.heartbeat(s.ownership()));
    }
}
