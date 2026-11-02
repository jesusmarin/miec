package com.miec.action;

import com.miec.action.integration.DestinationEvidenceSource;
import com.miec.action.integration.DestinationEvidenceSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Valida selección confiable, vínculo persistido y permisos alrededor de la consulta externa. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:destination_verification;DB_CLOSE_DELAY=-1")
class DestinationEvidenceVerificationServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionRecoveryAccess access;
    @Autowired private ExecutionEvidenceService evidence;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionOperationRegistry operations;

    /** Identidades del escenario sin exponer un adaptador en el comando público. */
    private record Scenario(ExecutionRecoveryTestSupport.Fixture fixture, UUID executionId,
                            DestinationEvidenceVerificationService.Command command) { }

    /** Prepara una operación real con snapshot e intento iniciado. */
    private Scenario scenario() {
        var fixture = ExecutionRecoveryTestSupport.fixture(jdbc);
        var operation = operations.prepare(fixture.command(), new ExecutionRequest("inventory", "application/json", "{}"));
        var pending = attempts.createProtectedAttempt(fixture.attemptCommand(), operation.key());
        var started = attempts.startAuthorizedAttempt(pending.executionId(), "PROCESS_EXECUTION");
        return new Scenario(fixture, started.executionId(), new DestinationEvidenceVerificationService.Command(
                UUID.randomUUID(), started.executionId(), fixture.reviewAuthorization(), "reviewer"));
    }

    /** Instala una fuente como configuración del servidor para este escenario aislado. */
    private DestinationEvidenceSource source(Function<IdempotentActionExecutor.Context, DestinationEvidenceSource.Observation> lookup) {
        return new DestinationEvidenceSource() {
            /** Identidad configurada, independiente de la solicitud entrante. */
            public String destinationId() { return "inventory"; }
            /** Ejecuta la consulta controlada por la prueba. */
            public Observation lookup(IdempotentActionExecutor.Context context) { return lookup.apply(context); }
        };
    }

    /** Compone el servicio con un registro inmutable, igual que el bootstrap de la aplicación. */
    private DestinationEvidenceVerificationService service(DestinationEvidenceSource... sources) {
        return new DestinationEvidenceVerificationService(access, evidence, new DestinationEvidenceSources(List.of(sources)));
    }

    /** Representa la confirmación autenticada de la clave y huella recibidas por la fuente. */
    private DestinationEvidenceSource.Observation applied(IdempotentActionExecutor.Context context) {
        return new DestinationEvidenceSource.Observation(context.operation().key(), context.request().destinationId(),
                context.request().fingerprint(), IdempotentActionExecutor.Status.APPLIED, "reservation:42");
    }

    /** La consulta no mantiene transacciones; registrar evidencia no resuelve ni renueva el intento. */
    @Test void shouldVerifyPersistedContextOutsideTransactionAndReplayEvidence() {
        var scenario = scenario();
        var before = jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?", scenario.executionId());
        var verifier = service(source(context -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(scenario.executionId(), context.ownership().executionId());
            assertEquals("{}", context.request().body());
            return applied(context);
        }));
        var proof = verifier.verifyApplied(scenario.command());
        assertEquals(proof, verifier.verifyApplied(scenario.command()));
        assertEquals(ExecutionEvidenceService.Source.DESTINATION_VERIFIED, proof.source());
        assertEquals(ExecutionEvidenceService.Outcome.EFFECT_APPLIED, proof.outcome());
        assertFalse(proof.occurredAt().isAfter(proof.receivedAt()));
        assertEquals(1, evidence.findByAttempt(scenario.executionId()).size());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?", scenario.executionId()));
    }

    /** Un destinatario no instalado se rechaza, sin fabricar evidencia. */
    @Test void shouldRejectUnconfiguredDestination() {
        var scenario = scenario();
        assertThrows(IllegalStateException.class, () -> service().verifyApplied(scenario.command()));
        assertTrue(evidence.findByAttempt(scenario.executionId()).isEmpty());
    }

    /** Un registro ambiguo se rechaza al configurar el servidor. */
    @Test void shouldRejectDuplicateSources() {
        assertThrows(IllegalArgumentException.class, () -> service(source(this::applied), source(this::applied)));
    }

    /** Ni ausencia visible ni incertidumbre se convierten en prueba de ausencia de efecto. */
    @ParameterizedTest @EnumSource(value=IdempotentActionExecutor.Status.class, names={"NOT_FOUND", "UNKNOWN"})
    void shouldRejectUnconfirmedObservations(IdempotentActionExecutor.Status status) {
        var scenario = scenario();
        var verifier = service(source(context -> new DestinationEvidenceSource.Observation(context.operation().key(),
                context.request().destinationId(), context.request().fingerprint(), status, null)));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class, () -> verifier.verifyApplied(scenario.command()));
        assertTrue(evidence.findByAttempt(scenario.executionId()).isEmpty());
    }

    /** La coincidencia de estado no basta: clave, destino y huella deben corresponder al snapshot. */
    @Test void shouldRejectForeignIdentityOrContent() {
        var scenario = scenario();
        for (int field = 0; field < 3; field++) {
            int mismatch = field;
            var verifier = service(source(context -> new DestinationEvidenceSource.Observation(
                    mismatch == 0 ? UUID.randomUUID() : context.operation().key(),
                    mismatch == 1 ? "other" : context.request().destinationId(),
                    mismatch == 2 ? "other" : context.request().fingerprint(), IdempotentActionExecutor.Status.APPLIED, "ref")));
            assertThrows(ExecutionAttemptService.AttemptDeniedException.class, () -> verifier.verifyApplied(scenario.command()));
        }
        assertTrue(evidence.findByAttempt(scenario.executionId()).isEmpty());
    }

    /** Un responsable inválido no puede usar el verificador como cliente remoto. */
    @Test void shouldAuthorizeBeforeCallingSource() {
        var scenario = scenario();
        var calls = new AtomicInteger();
        var verifier = service(source(context -> { calls.incrementAndGet(); return applied(context); }));
        var invalid = new DestinationEvidenceVerificationService.Command(UUID.randomUUID(), scenario.executionId(),
                scenario.fixture().reviewAuthorization(), "intruder");
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class, () -> verifier.verifyApplied(invalid));
        assertEquals(0, calls.get());
    }

    /** Revocar durante la consulta impide persistir aunque el destinatario confirme aplicación. */
    @Test void shouldReauthorizeAfterExternalLookup() {
        var scenario = scenario();
        var verifier = service(source(context -> {
            jdbc.update("UPDATE authorizations SET status='REVOKED' WHERE authorization_id=?", scenario.fixture().reviewAuthorization());
            return applied(context);
        }));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class, () -> verifier.verifyApplied(scenario.command()));
        assertTrue(evidence.findByAttempt(scenario.executionId()).isEmpty());
    }

    /** Un fallo remoto no deja evidencia parcial ni cambia el intento. */
    @Test void shouldNotPersistWhenSourceFails() {
        var scenario = scenario();
        assertThrows(IllegalStateException.class, () -> service(source(context -> {
            throw new IllegalStateException("transport unavailable");
        })).verifyApplied(scenario.command()));
        assertTrue(evidence.findByAttempt(scenario.executionId()).isEmpty());
    }

    /** Una transacción del llamador se rechaza antes de tomar bloqueos o hacer consultas de red. */
    @Test void shouldRejectOuterTransaction() {
        var scenario = scenario();
        access.transaction.executeWithoutResult(tx -> assertThrows(IllegalStateException.class,
                () -> service(source(context -> fail("No debe consultar"))).verifyApplied(scenario.command())));
    }

    /** Un intento heredado sin operación no recibe una identidad inventada. */
    @Test void shouldRejectAttemptWithoutRequestSnapshot() {
        var fixture = ExecutionRecoveryTestSupport.fixture(jdbc);
        var pending = attempts.createAttempt(fixture.attemptCommand());
        var started = attempts.startAuthorizedAttempt(pending.executionId(), "PROCESS_EXECUTION");
        var command = new DestinationEvidenceVerificationService.Command(UUID.randomUUID(), started.executionId(),
                fixture.reviewAuthorization(), "reviewer");
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,
                () -> service(source(context -> fail("No debe consultar"))).verifyApplied(command));
    }

    /** Dos consultas simultáneas del mismo evento conservan contenido e instante de una sola evidencia. */
    @Test void shouldDeduplicateConcurrentVerifications() throws Exception {
        var scenario = scenario();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var verifier = service(source(context -> {
            try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return applied(context);
        }));
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> verifier.verifyApplied(scenario.command()));
            var second = workers.submit(() -> verifier.verifyApplied(scenario.command()));
            assertEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, evidence.findByAttempt(scenario.executionId()).size());
    }
}
