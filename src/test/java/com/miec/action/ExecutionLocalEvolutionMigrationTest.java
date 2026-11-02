package com.miec.action;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Valida el incremento local completo sobre un historial anterior a las nuevas columnas. */
class ExecutionLocalEvolutionMigrationTest {
    /** V008-V009 preservan claves e intentos sin inventar cuerpos ni cancelaciones. */
    @Test @DisplayName("Actualizar V007 a V009 conserva operación histórica e intento PENDING")
    void shouldUpgradeHistoricalOperationsAndPendingAttempts() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:local_migration_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("007").load().migrate();
            var jdbc=new JdbcTemplate(source);var f=ExecutionRecoveryTestSupport.fixture(jdbc);
            UUID key=UUID.randomUUID(),executionId=UUID.randomUUID();
            jdbc.update("INSERT INTO execution_operation(operation_key,plan_id,planned_action_id,destination_id,request_fingerprint,created_at) VALUES (?,?,?,'legacy',?,CURRENT_TIMESTAMP)",key,f.plan(),f.action(),"a".repeat(64));
            jdbc.update("""
                    INSERT INTO execution_attempt(execution_id,planned_action_id,plan_id,cycle_id,authorization_id,
                        attempt_number,execution_status,effect_outcome,actor_id,operation_key)
                    VALUES (?,?,?,?,?,1,'PENDING','EFFECT_UNKNOWN','legacy',?)
                    """,executionId,f.action(),f.plan(),f.cycle(),f.executeAuthorization(),key);
            var operation=jdbc.queryForMap("SELECT * FROM execution_operation WHERE operation_key=?",key);
            var attempt=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",executionId);
            var flyway=Flyway.configure().dataSource(source).target("009").load();
            assertEquals(2,flyway.migrate().migrationsExecuted);
            assertEquals(attempt,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",executionId));
            var upgraded=jdbc.queryForMap("SELECT * FROM execution_operation WHERE operation_key=?",key);
            operation.forEach((column,value)->assertEquals(value,upgraded.get(column)));
            assertNull(upgraded.get("REQUEST_BODY"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM execution_pending_review",Integer.class));
            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("""
                    INSERT INTO execution_pending_review(review_id,execution_id,authorization_id,reviewed_by,
                        rationale,original_created_at,reviewed_at) VALUES (?,?,?,'reviewer','invalid',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """,UUID.randomUUID(),UUID.randomUUID(),f.reviewAuthorization()));
        } finally { source.destroy(); }
    }
}
