package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Comprueba la conservación del incidente de heartbeat en el coordinador. */
class AuthorizedActionHeartbeatFailureTest {
    /**
     * Una renovación fallida interrumpe al ejecutor. Si este lanza otra excepción,
     * se conserva como principal y el fallo del heartbeat queda suprimido.
     * Nunca se registra COMPLETED ni se vuelve a invocar el efecto.
     */
    @Test @DisplayName("El coordinador conserva ambos fallos al cancelar por heartbeat")
    void shouldPreserveHeartbeatFailureWhenExecutorThrowsOnInterruption() throws Exception {
        var attempts=mock(ExecutionAttemptService.class);
        var activity=mock(ExecutionActivityService.class);
        when(activity.heartbeatInterval()).thenReturn(Duration.ofMillis(10));
        var databaseFailure=new IllegalStateException("heartbeat failed");
        var executionFailure=new IllegalStateException("execution interrupted");
        when(activity.heartbeat(any())).thenThrow(databaseFailure);
        Instant now=Instant.now();
        var started=new ExecutionAttemptService.AttemptSnapshot(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,
                ExecutionRetryPolicy.ExecutionStatus.IN_PROGRESS,ExecutionRetryPolicy.EffectOutcome.EFFECT_UNKNOWN,
                "test",now,null,null,null,1L,"executor",1L,now,now.plusSeconds(30));
        when(attempts.createAttempt(any())).thenReturn(started);
        when(attempts.startAuthorizedAttempt(any(),any())).thenReturn(started);
        try(var supervisor=new ExecutionHeartbeatSupervisor(activity);
            var pool=Executors.newSingleThreadExecutor()) {
            var coordinator=new AuthorizedActionExecutionService(attempts,supervisor,mock(ExecutionEvidenceService.class));
            var future=pool.submit(()->coordinator.executeAuthorizedTracked(
                    new AuthorizedActionExecutionService.ExecutionCommand(started.plannedActionId(),started.planId(),
                            started.cycleId(),started.authorizationId(),"PROCESS_EXECUTION","test"),()-> {
                        try { new CountDownLatch(1).await(5,TimeUnit.SECONDS); fail("Debe cancelarse"); }
                        catch(InterruptedException expected) { throw executionFailure; }
                    }));
            var failure=assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS));
            assertSame(executionFailure,failure.getCause());
            assertEquals(1,executionFailure.getSuppressed().length);
            assertSame(databaseFailure,executionFailure.getSuppressed()[0]);
            verify(attempts).markUnknown(started.ownership(),"execution-failure:"+started.executionId());
            verify(attempts,never()).completeAttempt(any(),any());
            verify(activity).heartbeat(started.ownership());
        }
    }
}
