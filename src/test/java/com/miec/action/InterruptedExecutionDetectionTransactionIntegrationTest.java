package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprueba visibilidad de intentos confirmados por el servicio real desde
 * una transacción posterior. No usa una transacción exterior de prueba:
 * ExecutionAttemptService confirma las transiciones con REQUIRES_NEW.
 * Los datos se eliminan en orden de dependencias al terminar.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:miec_detection_transactions;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DisplayName("IR-009 - Visibilidad transaccional del detector")
class InterruptedExecutionDetectionTransactionIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * Crea las referencias autorizadas, confirma un intento IN_PROGRESS
     * mediante el servicio real y lo consulta en una transacción nueva.
     *
     * <p>El reloj del detector se sitúa treinta minutos después del inicio
     * confirmado, evitando esperas y modificaciones artificiales del intento.
     * Verifica el mapeo completo y que la consulta no cambie sus columnas.
     */
    @Test
    @DisplayName("Detecta desde otra transacción un intento confirmado por el servicio")
    void shouldDetectAttemptCommittedByServiceInAnotherTransaction() {
        UUID systemId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

        try {
            jdbc.update("INSERT INTO system_objective(system_id, name) VALUES (?, ?)",
                    systemId, "Detector transaction test");
            jdbc.update("INSERT INTO cycle(cycle_id, system_id) VALUES (?, ?)", cycleId, systemId);
            jdbc.update("""
                    INSERT INTO intervention_plan(plan_id, cycle_id, status, actions)
                    VALUES (?, ?, ?, ? FORMAT JSON)
                    """, planId, cycleId, "PROPOSED", """
                    {"schemaVersion":"01","actions":[{"actionId":"%s","actionType":"PROCESS_EXECUTION"}]}
                    """.formatted(actionId));
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO authorizations(authorization_id, plan_id, action_id,
                        requested_by, approved_by, scope, status, issued_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, authorizationId, planId, actionId, "requester", "approver",
                    "EXECUTE_ACTION", "APPROVED", Timestamp.from(now.minusSeconds(60)),
                    Timestamp.from(now.plusSeconds(3600)));

            var pending = attempts.createAttempt(new ExecutionAttemptService.CreateAttemptCommand(
                    actionId, planId, cycleId, authorizationId, "PROCESS_EXECUTION", "test"));
            var started = attempts.startAuthorizedAttempt(pending.executionId(), "PROCESS_EXECUTION");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            String select = "SELECT * FROM execution_attempt WHERE execution_id = ?";
            var before = jdbc.queryForMap(select, started.executionId());

            Duration minimumAge = Duration.ofMinutes(30);
            var detector = new InterruptedExecutionDetectionService(jdbc, Clock.fixed(
                    started.startedAt().plus(minimumAge), ZoneOffset.UTC));
            var reader = new TransactionTemplate(transactionManager);
            reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            reader.setReadOnly(true);

            var candidates = reader.execute(status -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return detector.findCandidates(minimumAge, 100);
            });

            assertNotNull(candidates);
            assertEquals(1, candidates.size());
            assertEquals(new InterruptedExecutionDetectionService.ReviewCandidate(
                    started.executionId(), planId, actionId, 1, started.startedAt()),
                    candidates.getFirst());
            assertEquals(before, jdbc.queryForMap(select, started.executionId()));
        } finally {
            jdbc.update("DELETE FROM execution_attempt WHERE plan_id = ?", planId);
            jdbc.update("DELETE FROM authorizations WHERE authorization_id = ?", authorizationId);
            jdbc.update("DELETE FROM intervention_plan WHERE plan_id = ?", planId);
            jdbc.update("DELETE FROM cycle WHERE cycle_id = ?", cycleId);
            jdbc.update("DELETE FROM system_objective WHERE system_id = ?", systemId);
        }
    }
}
