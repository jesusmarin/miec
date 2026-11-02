package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static com.miec.action.ExecutionEvidenceService.Outcome.*;
import static com.miec.action.ExecutionReconciliationPolicy.Resolution.*;
import static org.junit.jupiter.api.Assertions.*;

/** Comprueba decisiones auditadas, autorización y exclusión concurrente con SQL real. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:reconciliation_test;DB_CLOSE_DELAY=-1")
class ExecutionReconciliationServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionEvidenceService evidence;
    @Autowired private ExecutionReconciliationService reconciliation;

    /** Reconciliar un intento incierto preserva el estado y registra una decisión idempotente. */
    @Test @DisplayName("Decisión auditada conserva estado original y se repite sin duplicar")
    void shouldPersistDecisionWithoutChangingAttempt() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        attempts.markUnknown(s.ownership(),"network timeout");
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",NO_EFFECT_CONFIRMED,"destination:absent",Instant.now());
        var command=new ExecutionReconciliationService.Command(s.executionId(),proof.id(),NO_EFFECT,f.reviewAuthorization(),"reviewer","Consulta confirmada al destinatario");
        var before=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId());
        var decision=reconciliation.decide(command);
        assertEquals(decision,reconciliation.decide(command));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId()));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->attempts.createAttempt(f.attemptCommand()));
        evidence.report(UUID.randomUUID(),s.ownership(),EFFECT_APPLIED,"contradictory late result",Instant.now());
        assertFalse(reconciliation.permitsProtectedRetry(s.executionId()));
    }

    /** Se rechazan permisos equivocados, ejecuciones activas y evidencia no verificada. */
    @Test @DisplayName("Decidir exige permiso de revisión, lease vencido y evidencia verificada")
    void shouldRejectUnauthorizedOrActiveDecisions() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        var report=evidence.report(UUID.randomUUID(),s.ownership(),EFFECT_APPLIED,"report",Instant.now());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reconciliation.decide(
                new ExecutionReconciliationService.Command(s.executionId(),report.id(),APPLIED,f.executeAuthorization(),"reviewer","invalid scope")));
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",EFFECT_APPLIED,"destination:applied",Instant.now());
        var command=new ExecutionReconciliationService.Command(s.executionId(),proof.id(),APPLIED,f.reviewAuthorization(),"reviewer","confirmed");
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reconciliation.decide(command));
        ExecutionRecoveryTestSupport.expire(jdbc,s.executionId());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reconciliation.decide(
                new ExecutionReconciliationService.Command(s.executionId(),report.id(),APPLIED,f.reviewAuthorization(),"reviewer","unverified")));
        assertEquals(APPLIED,reconciliation.decide(command).command().resolution());
    }

    /** Dos solicitudes de la misma resolución confirman una sola fila y una identidad. */
    @Test @DisplayName("Decisiones concurrentes son únicas e idempotentes")
    void shouldSerializeConcurrentDecisions() throws Exception {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        attempts.markUnknown(s.ownership(),"timeout");
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",NO_EFFECT_CONFIRMED,"absent",Instant.now());
        var command=new ExecutionReconciliationService.Command(s.executionId(),proof.id(),NO_EFFECT,f.reviewAuthorization(),"reviewer","verified");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<ExecutionReconciliationService.Decision> task=()->{barrier.await(5,TimeUnit.SECONDS);return reconciliation.decide(command);};
            var a=pool.submit(task);var b=pool.submit(task);
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM execution_reconciliation WHERE execution_id=?",Integer.class,s.executionId()));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reconciliation.decide(
                new ExecutionReconciliationService.Command(s.executionId(),proof.id(),APPLIED,f.reviewAuthorization(),"reviewer","different")));
    }

    /** La autorización de revisión se revalida aunque la evidencia ya esté registrada. */
    @Test @DisplayName("Revocar el permiso después de verificar impide la decisión")
    void shouldRevalidateReviewerAuthorization() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        attempts.markUnknown(s.ownership(),"timeout");
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",NO_EFFECT_CONFIRMED,"absent",Instant.now());
        jdbc.update("UPDATE authorizations SET revoked_at=CURRENT_TIMESTAMP WHERE authorization_id=?",f.reviewAuthorization());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reconciliation.decide(
                new ExecutionReconciliationService.Command(s.executionId(),proof.id(),NO_EFFECT,f.reviewAuthorization(),"reviewer","revoked")));
        assertTrue(reconciliation.find(s.executionId()).isEmpty());
    }

    /** Evidencia verificada contradictoria impide resolver, sin descartar ninguna fila. */
    @Test @DisplayName("Las contradicciones impiden decisiones automáticas")
    void shouldRejectContradictoryEvidence() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        attempts.markUnknown(s.ownership(),"timeout");
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",NO_EFFECT_CONFIRMED,"absent",Instant.now());
        evidence.report(UUID.randomUUID(),s.ownership(),EFFECT_APPLIED,"applied",Instant.now());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reconciliation.decide(
                new ExecutionReconciliationService.Command(s.executionId(),proof.id(),NO_EFFECT,f.reviewAuthorization(),"reviewer","conflict")));
        assertTrue(reconciliation.find(s.executionId()).isEmpty());
    }
}
