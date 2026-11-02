package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Revisión manual de pendientes, preservación de historia y exclusión con el inicio. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:pending_review;DB_CLOSE_DELAY=-1")
class PendingExecutionReviewServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private PendingExecutionReviewService reviews;
    @Autowired private ExecutionOutcomeQueryService outcomes;

    /** Cancela trabajo no iniciado, bloquea su inicio y admite otro intento con nueva autorización validada. */
    @Test @DisplayName("Cancelar PENDING antiguo no inventa fechas de ejecución y conserva trazabilidad")
    void shouldCancelWithoutRewritingPendingAttempt() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var p=attempts.createAttempt(f.attemptCommand());
        jdbc.update("UPDATE execution_attempt SET created_at=DATEADD('HOUR',-1,CURRENT_TIMESTAMP) WHERE execution_id=?",p.executionId());
        var before=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",p.executionId());
        assertTrue(reviews.findCandidates(Duration.ofMinutes(30),100).contains(p.executionId()));
        var review=reviews.cancelBeforeStart(p.executionId(),f.reviewAuthorization(),"reviewer","never started",Duration.ofMinutes(30));
        assertEquals(review,reviews.cancelBeforeStart(p.executionId(),f.reviewAuthorization(),"reviewer","never started",Duration.ofMinutes(30)));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",p.executionId()));
        assertFalse(reviews.findCandidates(Duration.ofMinutes(30),100).contains(p.executionId()));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->attempts.startAuthorizedAttempt(p.executionId(),"PROCESS_EXECUTION"));
        attempts.rejectPendingBeforeExecution(p.executionId(),"late coordinator cleanup");
        assertEquals(before,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",p.executionId()));
        assertEquals(ExecutionOutcomeQueryService.EffectiveOutcome.CANCELLED_PENDING,outcomes.findAttempt(p.executionId()).orElseThrow().outcome());
        assertEquals(2,attempts.createAttempt(f.attemptCommand()).attemptNumber());
    }

    /** Recientes, permisos erróneos y trabajo iniciado nunca se cancelan como ausencia de efectos. */
    @Test @DisplayName("Revisión rechaza pendientes recientes, permisos inválidos y ejecuciones iniciadas")
    void shouldRejectInvalidReviews() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var p=attempts.createAttempt(f.attemptCommand());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reviews.cancelBeforeStart(p.executionId(),f.reviewAuthorization(),"reviewer","recent",Duration.ofMinutes(30)));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reviews.cancelBeforeStart(p.executionId(),f.executeAuthorization(),"reviewer","wrong scope",Duration.ofMinutes(30)));
        attempts.startAuthorizedAttempt(p.executionId(),"PROCESS_EXECUTION");
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->reviews.cancelBeforeStart(p.executionId(),f.reviewAuthorization(),"reviewer","already running",Duration.ofNanos(1)));
        assertTrue(reviews.find(p.executionId()).isEmpty());
    }

    /** El bloqueo común impide que cancelación e inicio confirmen simultáneamente. */
    @Test @DisplayName("Inicio y cancelación concurrentes tienen un único ganador")
    void shouldSerializeCancellationAndStart() throws Exception {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var p=attempts.createAttempt(f.attemptCommand());
        jdbc.update("UPDATE execution_attempt SET created_at=DATEADD('HOUR',-1,CURRENT_TIMESTAMP) WHERE execution_id=?",p.executionId());
        int succeeded=0,rejected=0;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            var start=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return attempts.startAuthorizedAttempt(p.executionId(),"PROCESS_EXECUTION");});
            var cancel=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return reviews.cancelBeforeStart(p.executionId(),f.reviewAuthorization(),"reviewer","stale",Duration.ofMinutes(30));});
            for(var future:java.util.List.of(start,cancel)) {
                try { assertNotNull(future.get(10,TimeUnit.SECONDS));succeeded++; }
                catch(ExecutionException failure) { assertInstanceOf(ExecutionAttemptService.AttemptDeniedException.class,failure.getCause());rejected++; }
            }
        }
        assertEquals(1,succeeded);assertEquals(1,rejected);
        boolean running=attempts.findHistory(f.plan(),f.action()).getFirst().status()==ExecutionRetryPolicy.ExecutionStatus.IN_PROGRESS;
        assertNotEquals(running,reviews.find(p.executionId()).isPresent());
    }
}
