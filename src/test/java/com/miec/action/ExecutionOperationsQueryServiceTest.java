package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Valida bandeja y métricas sobre estados originales y resoluciones derivadas. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:operations_query;DB_CLOSE_DELAY=-1")
class ExecutionOperationsQueryServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionEvidenceService evidence;
    @Autowired private ExecutionReconciliationService reconciliation;
    @Autowired private PendingExecutionReviewService pendingReviews;
    @Autowired private ExecutionOperationsQueryService operations;
    private final Duration age=Duration.ofMinutes(30);

    /** Empates de fecha no duplican ni omiten filas; un plan ajeno queda fuera de la página. */
    @Test @DisplayName("Cursor estable recorre todos los intentos del plan sin duplicar empates")
    void shouldPaginateUsingTimestampAndIdentity() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var expected=new HashSet<UUID>();
        // Los intentos adicionales son filas de fixture; no se ejecutan ni eluden la política productiva.
        for(int number=1;number<=7;number++) {
            UUID id=UUID.randomUUID();expected.add(id);
            jdbc.update("""
                    INSERT INTO execution_attempt(execution_id,planned_action_id,plan_id,cycle_id,authorization_id,
                        attempt_number,execution_status,effect_outcome,actor_id,created_at)
                    VALUES (?,?,?,?,?,?,'PENDING','EFFECT_UNKNOWN','fixture',TIMESTAMP '2026-01-01 00:00:00')
                    """,id,f.action(),f.plan(),f.cycle(),f.executeAuthorization(),number);
        }
        var other=ExecutionRecoveryTestSupport.fixture(jdbc);attempts.createAttempt(other.attemptCommand());
        ExecutionOperationsQueryService.Cursor cursor=null;var seen=new HashSet<UUID>();int pages=0;
        do {
            var page=operations.page(f.plan(),ExecutionOperationsQueryService.Filter.ALL,cursor,3,age);pages++;
            assertTrue(page.items().size()<=3);
            for(var view:page.items()) assertTrue(seen.add(view.executionId()),"No debe repetir identidades");
            cursor=page.nextCursor();assertTrue(pages<=3);
        } while(cursor!=null);
        assertEquals(expected,seen);assertEquals(3,pages);
        assertThrows(IllegalArgumentException.class,()->operations.page(f.plan(),ExecutionOperationsQueryService.Filter.ALL,null,101,age));
    }

    /** Resolver excluye de revisión; una contradicción posterior vuelve a mostrar el caso. */
    @Test @DisplayName("Bandeja y métricas distinguen resolución y contradicción posterior")
    void shouldTrackResolutionAndContradictions() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        attempts.markUnknown(s.ownership(),"timeout");
        assertEquals(1,operations.page(f.plan(),ExecutionOperationsQueryService.Filter.NEEDS_REVIEW,null,10,age).items().size());
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",ExecutionEvidenceService.Outcome.EFFECT_APPLIED,"result",Instant.now());
        reconciliation.decide(new ExecutionReconciliationService.Command(s.executionId(),proof.id(),ExecutionReconciliationPolicy.Resolution.APPLIED,f.reviewAuthorization(),"reviewer","verified"));
        assertTrue(operations.page(f.plan(),ExecutionOperationsQueryService.Filter.NEEDS_REVIEW,null,10,age).items().isEmpty());
        assertEquals(1L,operations.metrics(f.plan(),age).byOutcome().get(ExecutionOutcomeQueryService.EffectiveOutcome.APPLIED));
        evidence.report(UUID.randomUUID(),s.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"conflict",Instant.now());
        var metrics=operations.metrics(f.plan(),age);
        assertEquals(1,metrics.needsReview());assertEquals(1L,metrics.byOutcome().get(ExecutionOutcomeQueryService.EffectiveOutcome.CONFLICT));
        assertEquals(1,operations.page(f.plan(),ExecutionOperationsQueryService.Filter.NEEDS_REVIEW,null,10,age).items().size());
    }

    /** Pendientes recientes no requieren revisión; cancelarlos mantiene historia pero cierra la incidencia. */
    @Test @DisplayName("Pendientes antiguos y cancelados tienen conteos operativos distintos")
    void shouldTrackStaleAndCancelledPending() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var p=attempts.createAttempt(f.attemptCommand());
        assertEquals(0,operations.metrics(f.plan(),age).needsReview());
        jdbc.update("UPDATE execution_attempt SET created_at=DATEADD('HOUR',-1,CURRENT_TIMESTAMP) WHERE execution_id=?",p.executionId());
        assertEquals(1,operations.metrics(f.plan(),age).needsReview());
        pendingReviews.cancelBeforeStart(p.executionId(),f.reviewAuthorization(),"reviewer","stale",age);
        var metrics=operations.metrics(f.plan(),age);
        assertEquals(0,metrics.needsReview());assertEquals(1L,metrics.byOutcome().get(ExecutionOutcomeQueryService.EffectiveOutcome.CANCELLED_PENDING));
        assertTrue(operations.page(f.plan(),ExecutionOperationsQueryService.Filter.NEEDS_REVIEW,null,10,age).items().isEmpty());
    }
}
