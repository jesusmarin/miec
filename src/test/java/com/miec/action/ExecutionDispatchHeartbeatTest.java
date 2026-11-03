package com.miec.action;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Un heartbeat fallido durante lookup bloquea POST aunque el adaptador ignore la interrupción cooperativa. */
class ExecutionDispatchHeartbeatTest {
    /** La salud retenida del supervisor, no solo el flag de interrupción, gobierna la admisión. */
    @Test void shouldRejectSendAfterLookupIgnoresHeartbeatInterruption() throws Exception {
        var activity=mock(ExecutionActivityService.class);var attempts=mock(ExecutionAttemptService.class);
        var operations=mock(ExecutionOperationRegistry.class);
        when(activity.heartbeatInterval()).thenReturn(Duration.ofMillis(10));
        UUID plan=UUID.randomUUID(),action=UUID.randomUUID(),cycle=UUID.randomUUID(),authorization=UUID.randomUUID(),executionId=UUID.randomUUID();
        var started=new ExecutionAttemptService.AttemptSnapshot(executionId,action,plan,cycle,authorization,1,
                ExecutionRetryPolicy.ExecutionStatus.IN_PROGRESS,ExecutionRetryPolicy.EffectOutcome.EFFECT_UNKNOWN,"author",Instant.now(),null,null,null,1L,
                "owner",1L,Instant.now(),Instant.now().plusSeconds(30));
        when(attempts.createProtectedAttempt(any(),any())).thenReturn(started);
        when(attempts.startAuthorizedAttempt(eq(executionId),anyString())).thenReturn(started);
        var request=new ExecutionRequest("test-destination","application/json","{}");
        var operation=new ExecutionOperationRegistry.Operation(UUID.randomUUID(),plan,action,request.destinationId(),request.fingerprint(),request);
        when(operations.prepare(any(),eq(request))).thenReturn(operation);
        var heartbeatFailure=new IllegalStateException("heartbeat test failure");
        when(activity.heartbeat(started.ownership())).thenThrow(heartbeatFailure);
        var sends=new AtomicInteger();
        var executor=new IdempotentActionExecutor() {
            /** Snapshot sin reconstrucción de bytes. */
            @Override public ExecutionRequest request() { return request; }
            /** Ignora deliberadamente la interrupción para reproducir un adaptador que no coopera. */
            @Override public Lookup lookup(Context context) {
                try { new CountDownLatch(1).await(2,TimeUnit.SECONDS); }
                catch(InterruptedException ignored) { Thread.interrupted(); }
                return new Lookup(Status.NOT_FOUND,null);
            }
            /** No debe alcanzarse después del fallo de heartbeat. */
            @Override public String execute(Context context) { sends.incrementAndGet(); return "unexpected"; }
        };
        try(var supervisor=new ExecutionHeartbeatSupervisor(activity);var pool=Executors.newSingleThreadExecutor()) {
            var coordinator=new AuthorizedActionExecutionService(attempts,supervisor,mock(ExecutionEvidenceService.class),mock(ExecutionIncidentService.class));
            var service=new IdempotentActionExecutionService(operations,coordinator,mock(ExecutionOutcomeQueryService.class));
            var pending=pool.submit(()->service.execute(new AuthorizedActionExecutionService.ExecutionCommand(action,plan,cycle,authorization,"PROCESS_EXECUTION","author"),executor));
            var failure=assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));
            var denied=assertInstanceOf(ExecutionDispatchRejectedException.class,failure.getCause());
            assertEquals(ExecutionDispatchRejectedException.Code.ATTEMPT_HEARTBEAT_FAILED,denied.code());
            assertSame(heartbeatFailure,denied.getCause()); assertEquals(0,sends.get());
            verify(attempts,never()).completeAttempt(any(),any());
        }
    }
}
