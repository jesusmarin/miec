package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Valida renovación periódica y cancelación con señales, sin una base de datos. */
class ExecutionHeartbeatSupervisorTest {
    /** Confirma renovaciones durante trabajo prolongado y ausencia de renovaciones tras close. */
    @Test @DisplayName("El supervisor renueva mientras trabaja y cancela al cerrar")
    void shouldRenewAndStop() throws Exception {
        var activity=mock(ExecutionActivityService.class);
        when(activity.heartbeatInterval()).thenReturn(Duration.ofMillis(10));
        var renewed=new CountDownLatch(2);
        when(activity.heartbeat(any())).thenAnswer(call->{renewed.countDown();return Instant.now();});
        try(var supervisor=new ExecutionHeartbeatSupervisor(activity)) {
            var scope=supervisor.supervise(new ExecutionActivityService.Ownership(UUID.randomUUID(),"test",1));
            try { assertTrue(renewed.await(5,TimeUnit.SECONDS)); }
            finally { scope.close(); }
            scope.checkHealthy();
            int count=mockingDetails(activity).getInvocations().size();
            // close es síncrono: ninguna renovación en curso puede sobrevivirlo.
            scope.close();
            assertEquals(count,mockingDetails(activity).getInvocations().size());
        }
    }

    /** Un fallo conserva su identidad, interrumpe el trabajo y se propaga al comprobar salud. */
    @Test @DisplayName("Fallo del heartbeat solicita cancelación cooperativa y conserva la causa")
    void shouldInterruptWorkerWhenRenewalFails() throws Exception {
        var activity=mock(ExecutionActivityService.class);
        when(activity.heartbeatInterval()).thenReturn(Duration.ofMillis(10));
        var failure=new IllegalStateException("database unavailable");
        when(activity.heartbeat(any())).thenThrow(failure);
        try(var supervisor=new ExecutionHeartbeatSupervisor(activity);
            var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()-> {
                try(var scope=supervisor.supervise(new ExecutionActivityService.Ownership(UUID.randomUUID(),"test",1))) {
                    try { new CountDownLatch(1).await(5,TimeUnit.SECONDS); fail("Debe interrumpirse"); }
                    catch(InterruptedException expected) { /* Cancelación observada. */ }
                    scope.close();
                    return assertThrows(IllegalStateException.class,scope::checkHealthy);
                }
            });
            assertSame(failure,result.get(10,TimeUnit.SECONDS));
            verify(activity).heartbeat(any());
        }
    }
}
