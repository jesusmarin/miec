package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.miec.action.ExecutionEvidenceService.Outcome.*;
import static com.miec.action.ExecutionReconciliationPolicy.Resolution.*;

/** Valida el flujo protegido con destinatario simulado; no certifica un sistema remoto real. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:idempotent_test;DB_CLOSE_DELAY=-1")
class IdempotentActionExecutionServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionEvidenceService evidence;
    @Autowired private ExecutionReconciliationService reconciliation;
    @Autowired private IdempotentActionExecutionService service;
    @Autowired private AuthorizedActionExecutionService coordinator;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** Destinatario que deduplica atómicamente por clave y permite simular respuestas perdidas. */
    private static final class Destination implements IdempotentActionExecutor {
        final ConcurrentMap<UUID,String> results=new ConcurrentHashMap<>();
        final AtomicInteger effects=new AtomicInteger(), sends=new AtomicInteger();
        final AtomicBoolean failBefore=new AtomicBoolean(), loseResponse=new AtomicBoolean();
        volatile boolean unknownLookup;
        /** Identidad de un único destinatario de prueba. */
        public String destinationId() { return "test-destination"; }
        /** Huella estable de una solicitud de prueba inmutable. */
        public String requestFingerprint() { return "a".repeat(64); }
        /** Consulta sin efectos; permite simular incertidumbre de comunicación. */
        public Lookup lookup(Context context) {
            if(unknownLookup) return new Lookup(Status.UNKNOWN,null);
            String result=results.get(context.operation().key());
            return result==null?new Lookup(Status.NOT_FOUND,null):new Lookup(Status.APPLIED,result);
        }
        /** Aplica clave y efecto juntos; perder la respuesta no elimina la deduplicación. */
        public String execute(Context context) {
            sends.incrementAndGet();
            if(failBefore.getAndSet(false)) throw new IllegalStateException("fallo previo al efecto");
            String result=results.computeIfAbsent(context.operation().key(),key->"result:"+effects.incrementAndGet());
            if(loseResponse.getAndSet(false)) throw new IllegalStateException("respuesta perdida después del efecto");
            return result;
        }
    }

    /** Prepara un intento incierto sin efecto y una resolución verificada NO_EFFECT. */
    private void resolveNoEffect(ExecutionRecoveryTestSupport.Fixture f,Destination destination) {
        destination.failBefore.set(true);
        assertThrows(IllegalStateException.class,()->service.execute(f.command(),destination));
        var s=attempts.findHistory(f.plan(),f.action()).getFirst();
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",NO_EFFECT_CONFIRMED,"destination:not-found",Instant.now());
        reconciliation.decide(new ExecutionReconciliationService.Command(s.executionId(),proof.id(),NO_EFFECT,f.reviewAuthorization(),"reviewer","Destinatario confirma ausencia"));
    }

    /** Una respuesta perdida se resuelve con evidencia aplicada, sin repetir el efecto. */
    @Test @DisplayName("Respuesta perdida tras aplicar el efecto se reconcilia sin duplicarlo")
    void shouldReconcileAppliedEffectAfterLostResponse() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var destination=new Destination();
        destination.loseResponse.set(true);
        assertThrows(IllegalStateException.class,()->service.execute(f.command(),destination));
        var s=attempts.findHistory(f.plan(),f.action()).getFirst();
        assertEquals(1,destination.effects.get());
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",EFFECT_APPLIED,"destination:result:1",Instant.now());
        var decision=reconciliation.decide(new ExecutionReconciliationService.Command(s.executionId(),proof.id(),APPLIED,f.reviewAuthorization(),"reviewer","Resultado consultado al destinatario"));
        assertEquals(APPLIED,decision.command().resolution());
        assertThrows(AuthorizedActionExecutionService.ExecutionDeniedException.class,()->service.execute(f.command(),destination));
        assertEquals(1,destination.effects.get());
        assertEquals(1,destination.sends.get());
        assertEquals(ExecutionRetryPolicy.ExecutionStatus.UNKNOWN,attempts.findHistory(f.plan(),f.action()).getFirst().status());
    }

    /** Reintentar exige resolución y reutiliza la clave original con número de intento nuevo. */
    @Test @DisplayName("Reintento NO_EFFECT usa la clave original y revalida IR-010")
    void shouldRetryWithOriginalKeyAfterReconciliation() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var destination=new Destination();
        resolveNoEffect(f,destination);
        var result=service.execute(f.command(),destination);
        assertEquals(2,result.attemptNumber());assertEquals("COMPLETED",result.status());
        assertEquals(1,destination.effects.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(DISTINCT operation_key) FROM execution_attempt WHERE plan_id=?",Integer.class,f.plan()));
        assertThrows(AuthorizedActionExecutionService.ExecutionDeniedException.class,()->coordinator.executeAuthorized(f.command(),()->fail("No debe usar ruta heredada")));
    }

    /** Dos solicitudes simultáneas tras reconciliar crean solo un reintento adicional. */
    @Test @DisplayName("Reintentos protegidos concurrentes producen un solo efecto")
    void shouldSerializeConcurrentProtectedRetries() throws Exception {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var destination=new Destination();
        resolveNoEffect(f,destination);
        int accepted=0,rejected=0;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<AuthorizedActionExecutionService.TrackedExecutionResult> task=()->{barrier.await(5,TimeUnit.SECONDS);return service.execute(f.command(),destination);};
            var a=pool.submit(task);var b=pool.submit(task);
            for(var future:java.util.List.of(a,b)) {
                try { assertEquals("COMPLETED",future.get(10,TimeUnit.SECONDS).status());accepted++; }
                catch(ExecutionException failure) { assertInstanceOf(AuthorizedActionExecutionService.ExecutionDeniedException.class,failure.getCause());rejected++; }
            }
        }
        assertEquals(1,accepted);assertEquals(1,rejected);assertEquals(1,destination.effects.get());
        assertEquals(2,attempts.findHistory(f.plan(),f.action()).size());
    }

    /** No se convierte incertidumbre histórica sin clave en un reintento automático. */
    @Test @DisplayName("La resolución de un intento antiguo sin clave no habilita ejecución")
    void shouldKeepUnprotectedLegacyAttemptBlocked() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var destination=new Destination();
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        attempts.markUnknown(s.ownership(),"legacy-timeout");
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",NO_EFFECT_CONFIRMED,"absent",Instant.now());
        reconciliation.decide(new ExecutionReconciliationService.Command(s.executionId(),proof.id(),NO_EFFECT,f.reviewAuthorization(),"reviewer","manual diagnosis"));
        assertThrows(AuthorizedActionExecutionService.ExecutionDeniedException.class,()->service.execute(f.command(),destination));
        assertEquals(0,destination.effects.get());
    }

    /** Un lookup incierto y una autorización revocada nunca disparan otro envío. */
    @Test @DisplayName("Incertidumbre del destinatario y revocación impiden efectos")
    void shouldRejectUnknownLookupAndRevokedAuthorization() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var destination=new Destination();
        destination.unknownLookup=true;
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->service.execute(f.command(),destination));
        assertEquals(0,destination.sends.get());
        var other=ExecutionRecoveryTestSupport.fixture(jdbc);var otherDestination=new Destination();
        resolveNoEffect(other,otherDestination);
        jdbc.update("UPDATE authorizations SET revoked_at=CURRENT_TIMESTAMP WHERE authorization_id=?",other.executeAuthorization());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->service.execute(other.command(),otherDestination));
        assertEquals(0,otherDestination.effects.get());
        assertEquals(1,attempts.findHistory(other.plan(),other.action()).size());
    }

    /** Rechaza transacciones exteriores antes de reservar claves o abrir un intento. */
    @Test @DisplayName("La ruta protegida rechaza transacción exterior sin reservar operación")
    void shouldRejectOuterTransactionBeforeReservingKey() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var destination=new Destination();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{service.execute(f.command(),destination);return null;}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM execution_operation WHERE plan_id=?",Integer.class,f.plan()));
        assertTrue(attempts.findHistory(f.plan(),f.action()).isEmpty());
    }

    /** Una contradicción posterior invalida la resolución para nuevos reintentos. */
    @Test @DisplayName("Evidencia contradictoria posterior impide usar la decisión NO_EFFECT")
    void shouldBlockRetryWhenLateEvidenceContradictsDecision() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var destination=new Destination();
        resolveNoEffect(f,destination);
        var s=attempts.findHistory(f.plan(),f.action()).getFirst();
        evidence.report(UUID.randomUUID(),s.ownership(),EFFECT_APPLIED,"late applied",Instant.now());
        assertThrows(AuthorizedActionExecutionService.ExecutionDeniedException.class,()->service.execute(f.command(),destination));
        assertEquals(0,destination.effects.get());
        assertEquals(1,attempts.findHistory(f.plan(),f.action()).size());
    }

    /** Un resultado tardío exitoso se conserva aun cuando su propiedad ya no permite finalizar. */
    @Test @DisplayName("El coordinador conserva evidencia aplicada después de vencer el lease")
    void shouldRecordLateResultWithoutRenewingOwnership() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->coordinator.executeAuthorizedTracked(f.command(),()->{
            var s=attempts.findHistory(f.plan(),f.action()).getFirst();
            ExecutionRecoveryTestSupport.expire(jdbc,s.executionId());
        }));
        var s=attempts.findHistory(f.plan(),f.action()).getFirst();
        assertEquals(ExecutionRetryPolicy.ExecutionStatus.IN_PROGRESS,s.status());
        assertTrue(evidence.findByAttempt(s.executionId()).stream().anyMatch(e->e.outcome()==EFFECT_APPLIED));
    }
}
