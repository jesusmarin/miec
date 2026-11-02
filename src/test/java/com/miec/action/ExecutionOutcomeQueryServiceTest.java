package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static com.miec.action.ExecutionOutcomeQueryService.EffectiveOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Comprueba resultados derivados sin alterar las filas originales. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:outcome_query;DB_CLOSE_DELAY=-1")
class ExecutionOutcomeQueryServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionEvidenceService evidence;
    @Autowired private ExecutionReconciliationService reconciliation;
    @Autowired private ExecutionOutcomeQueryService query;

    /** Una resolución aplicada conserva UNKNOWN original y expone su referencia verificada. */
    @Test @DisplayName("El resultado efectivo incorpora reconciliación sin cambiar UNKNOWN")
    void shouldExposeReconciledResultWithoutRewritingHistory() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        attempts.markUnknown(s.ownership(),"timeout");
        assertEquals(UNKNOWN,query.findAttempt(s.executionId()).orElseThrow().outcome());
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",
                ExecutionEvidenceService.Outcome.EFFECT_APPLIED,"result:external",Instant.now());
        reconciliation.decide(new ExecutionReconciliationService.Command(s.executionId(),proof.id(),
                ExecutionReconciliationPolicy.Resolution.APPLIED,f.reviewAuthorization(),"reviewer","confirmed"));
        var before=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId());
        var view=query.findLatest(f.plan(),f.action()).orElseThrow();
        assertEquals("UNKNOWN",view.originalStatus());assertEquals(APPLIED,view.outcome());
        assertEquals("result:external",view.resultReference());
        assertEquals(before,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",s.executionId()));
        evidence.report(UUID.randomUUID(),s.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradiction",Instant.now());
        assertEquals(CONFLICT,query.findAttempt(s.executionId()).orElseThrow().outcome());
        assertNull(query.findAttempt(s.executionId()).orElseThrow().resultReference());
    }

    /** Pending, ejecución vigente, vencimiento y cierre confirmado tienen resultados diferentes. */
    @Test @DisplayName("La proyección distingue pendiente, activo, vencido y aplicado")
    void shouldDistinguishExecutionLifecycle() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);var p=attempts.createAttempt(f.attemptCommand());
        assertEquals(PENDING,query.findAttempt(p.executionId()).orElseThrow().outcome());
        var s=attempts.startAuthorizedAttempt(p.executionId(),"PROCESS_EXECUTION");
        assertEquals(RUNNING,query.findAttempt(s.executionId()).orElseThrow().outcome());
        attempts.completeAttempt(s.ownership(),"result:1");
        assertEquals(APPLIED,query.findAttempt(s.executionId()).orElseThrow().outcome());
        assertEquals("result:1",query.findAttempt(s.executionId()).orElseThrow().resultReference());
        evidence.report(UUID.randomUUID(),s.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradiction",Instant.now());
        assertEquals(CONFLICT,query.findAttempt(s.executionId()).orElseThrow().outcome());
        var other=ExecutionRecoveryTestSupport.fixture(jdbc);
        var expired=attempts.startAuthorizedAttempt(attempts.createAttempt(other.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        ExecutionRecoveryTestSupport.expire(jdbc,expired.executionId());
        assertEquals(EXPIRED,query.findAttempt(expired.executionId()).orElseThrow().outcome());
        assertTrue(query.findAttempt(UUID.randomUUID()).isEmpty());
    }

    /** NO_EFFECT se expone como resolución y no como mutación del intento incierto. */
    @Test @DisplayName("Resolución sin efectos conserva estado original y no inventa resultado")
    void shouldExposeNoEffectResolution() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var s=attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
        attempts.markUnknown(s.ownership(),"timeout");
        var proof=evidence.verified(UUID.randomUUID(),s.executionId(),f.reviewAuthorization(),"reviewer",
                ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"absent",Instant.now());
        reconciliation.decide(new ExecutionReconciliationService.Command(s.executionId(),proof.id(),
                ExecutionReconciliationPolicy.Resolution.NO_EFFECT,f.reviewAuthorization(),"reviewer","verified"));
        var view=query.findAttempt(s.executionId()).orElseThrow();
        assertEquals(NO_EFFECT,view.outcome());assertEquals("UNKNOWN",view.originalStatus());assertNull(view.resultReference());
    }
}
