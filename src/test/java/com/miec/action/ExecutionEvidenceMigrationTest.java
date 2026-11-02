package com.miec.action;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Prueba la ampliación aditiva V005 conservando una base V004 con historial. */
class ExecutionEvidenceMigrationTest {
    /** Conserva la fila previa, deja la tabla vacía y aplica FK y restricciones de fuente. */
    @Test @DisplayName("V005 conserva historial V004 y valida procedencia de evidencias")
    void shouldUpgradeWithoutInventingEvidence() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:evidence_migration_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("004").load().migrate();
            var jdbc=new JdbcTemplate(source);
            var f=ExecutionRecoveryTestSupport.fixture(jdbc);
            UUID id=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO execution_attempt(execution_id,planned_action_id,plan_id,cycle_id,authorization_id,
                        attempt_number,execution_status,effect_outcome,actor_id)
                    VALUES (?,?,?,?,?,1,'PENDING','EFFECT_UNKNOWN','worker')
                    """,id,f.action(),f.plan(),f.cycle(),f.executeAuthorization());
            var before=jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",id);
            assertEquals(1,Flyway.configure().dataSource(source).target("005").load().migrate().migrationsExecuted);
            assertEquals(before,jdbc.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",id));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM execution_evidence",Integer.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("""
                    INSERT INTO execution_evidence(evidence_id,execution_id,source_type,reported_by,effect_outcome,
                        external_reference,occurred_at,received_at)
                    VALUES (?,?,'EXECUTOR_REPORT','worker','EFFECT_UNKNOWN','ref',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """,UUID.randomUUID(),id));
        } finally { source.destroy(); }
    }
}
