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
import static org.junit.jupiter.api.Assertions.*;

/** Valida recepción tardía, procedencia y deduplicación con transacciones reales. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:evidence_test;DB_CLOSE_DELAY=-1")
class ExecutionEvidenceServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionEvidenceService evidence;

    /** La observacion interna usa una fecha de recepcion unica, deduplica y no acredita al destinatario. */
    @Test @DisplayName("Reporte interno usa reloj de base y conserva fecha e identidad al deduplicar")
    void shouldDateInternalObservationWithDatabaseClock() {
        var fixture=ExecutionRecoveryTestSupport.fixture(jdbc);
        var attempt=attempts.startAuthorizedAttempt(attempts.createAttempt(fixture.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        UUID id=UUID.randomUUID();
        var first=evidence.reportObservation(id,attempt.ownership(),EFFECT_APPLIED,"internal-receipt");
        assertEquals(first.receivedAt(),first.occurredAt());
        assertEquals(ExecutionEvidenceService.Source.EXECUTOR_REPORT,first.source());
        assertNull(first.authorizationId());
        assertEquals(first,evidence.reportObservation(id,attempt.ownership(),EFFECT_APPLIED,"internal-receipt"));
        assertEquals(1,evidence.findByAttempt(attempt.executionId()).size());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,
                () -> evidence.reportObservation(id,attempt.ownership(),EFFECT_UNKNOWN,"internal-receipt"));
    }

    /** Un propietario vencido reporta sin renovar ni cambiar las columnas del intento. */
    @Test @DisplayName("Reporte tardío conserva intento y deduplica el mismo contenido")
    void shouldAcceptLateReportIdempotently() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        ExecutionRecoveryTestSupport.expire(jdbc,s.executionId());
        var before=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId());
        UUID event=UUID.randomUUID(); Instant occurred=Instant.now().minusSeconds(1);
        var first=evidence.report(event,s.ownership(),EFFECT_APPLIED,"destination:1",occurred);
        assertEquals(first,evidence.report(event,s.ownership(),EFFECT_APPLIED,"destination:1",occurred));
        assertEquals(1,evidence.findByAttempt(s.executionId()).size());
        assertEquals(before,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId()));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->evidence.report(event,s.ownership(),EFFECT_UNKNOWN,"destination:1",occurred));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->attempts.createAttempt(f.attemptCommand()));
    }

    /** Identidad incorrecta y fecha futura se rechazan; reportes contradictorios no se borran. */
    @Test @DisplayName("La evidencia valida procedencia y conserva contradicciones")
    void shouldValidateProvenanceAndRetainContradictions() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->evidence.report(UUID.randomUUID(),
                new ExecutionActivityService.Ownership(s.executionId(),"other",s.fencingToken()),EFFECT_UNKNOWN,"ref",Instant.now()));
        assertThrows(IllegalArgumentException.class,()->evidence.report(UUID.randomUUID(),s.ownership(),EFFECT_UNKNOWN,"ref",Instant.now().plusSeconds(60)));
        evidence.report(UUID.randomUUID(),s.ownership(),EFFECT_APPLIED,"report",Instant.now());
        evidence.report(UUID.randomUUID(),s.ownership(),NO_EFFECT_CONFIRMED,"other report",Instant.now());
        assertEquals(2,evidence.findByAttempt(s.executionId()).size());
    }

    /** Solo el responsable con alcance de revisión puede registrar una verificación. */
    @Test @DisplayName("Verificación exige autorización vigente y responsable aprobado")
    void shouldRequireVerificationAuthority() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->evidence.verified(UUID.randomUUID(),s.executionId(),f.executeAuthorization(),"reviewer",NO_EFFECT_CONFIRMED,"verified",Instant.now()));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"intruder",NO_EFFECT_CONFIRMED,"verified",Instant.now()));
        var verified=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",NO_EFFECT_CONFIRMED,"verified",Instant.now());
        assertEquals(ExecutionEvidenceService.Source.DESTINATION_VERIFIED,verified.source());
    }

    /** Dos recepciones simultáneas del mismo evento generan una sola fila. */
    @Test @DisplayName("La recepción concurrente del mismo evento es idempotente")
    void shouldDeduplicateConcurrentReports() throws Exception {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        UUID id=UUID.randomUUID(); Instant occurred=Instant.now();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<ExecutionEvidenceService.Evidence> task=()->{barrier.await(5,TimeUnit.SECONDS);return evidence.report(id,s.ownership(),EFFECT_UNKNOWN,"ref",occurred);};
            var a=pool.submit(task);var b=pool.submit(task);
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,evidence.findByAttempt(s.executionId()).size());
    }
}
