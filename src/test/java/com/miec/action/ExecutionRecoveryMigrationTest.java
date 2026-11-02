package com.miec.action;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Valida la entrega completa de recuperación sobre una instalación C08.2 con historial. */
class ExecutionRecoveryMigrationTest {
    /** V005-V007 conservan intentos existentes y no inventan operaciones o decisiones. */
    @Test @DisplayName("Actualizar V004 a V007 conserva historial e instala las nuevas relaciones")
    void shouldUpgradeC082WithHistoricalAttempts() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:recovery_migration_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("004").load().migrate();
            var jdbc=new JdbcTemplate(source);var f=ExecutionRecoveryTestSupport.fixture(jdbc);
            UUID execution=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO execution_attempt(execution_id,planned_action_id,plan_id,cycle_id,authorization_id,
                        attempt_number,execution_status,effect_outcome,actor_id,started_at,completed_at)
                    VALUES (?,?,?,?,?,1,'UNKNOWN','EFFECT_UNKNOWN','legacy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """,execution,f.action(),f.plan(),f.cycle(),f.executeAuthorization());
            var before=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",execution);
            var flyway=Flyway.configure().dataSource(source).target("007").load();
            assertEquals(3,flyway.migrate().migrationsExecuted);
            var after=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",execution);
            before.forEach((key,value)->assertEquals(value,after.get(key)));
            assertNull(after.get("OPERATION_KEY"));
            for(String table:new String[]{"execution_evidence","execution_reconciliation","execution_operation"}) {
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            }
            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                    "UPDATE execution_attempt SET operation_key=? WHERE execution_id=?",UUID.randomUUID(),execution));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("""
                    INSERT INTO execution_reconciliation(decision_id,execution_id,evidence_id,resolution,
                        reviewed_by,authorization_id,rationale,original_status,decided_at)
                    VALUES (?,?,?,'NO_EFFECT','reviewer',?,'missing evidence','UNKNOWN',CURRENT_TIMESTAMP)
                    """,UUID.randomUUID(),execution,UUID.randomUUID(),f.reviewAuthorization()));
        } finally { source.destroy(); }
    }
}
