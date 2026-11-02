package com.miec.action;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Valida V004 sobre instalaciones vacías y datos previos, sin Spring. */
class ExecutionOwnershipMigrationTest {
    /** Instala las cuatro migraciones y comprueba el reloj y las columnas nuevas. */
    @Test
    @DisplayName("V004 instala propiedad y reloj de base de datos desde cero")
    void shouldMigrateEmptyDatabase() {
        String url = "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var source = new SingleConnectionDataSource(url, "sa", "", true);
        Flyway flyway = Flyway.configure().dataSource(source).target("004").load();
        assertEquals(4, flyway.migrate().migrationsExecuted);
        var jdbc = new JdbcTemplate(source);
        assertNotNull(jdbc.queryForObject("SELECT miec_wall_clock()", Object.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM execution_attempt "
                + "WHERE executor_id IS NOT NULL OR fencing_token IS NOT NULL", Integer.class));
        assertTrue(flyway.validateWithResult().validationSuccessful);
    }

    /** Conserva intentos históricos y rechaza propiedad parcial o tokens inválidos. */
    @Test
    @DisplayName("V004 conserva datos V003 y exige propiedad completa y coherente")
    void shouldPreserveLegacyDataAndEnforceConstraints() {
        String url = "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var source = new SingleConnectionDataSource(url, "sa", "", true);
        Flyway.configure().dataSource(source).target("003").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID system = UUID.randomUUID(), cycle = UUID.randomUUID(), plan = UUID.randomUUID();
        UUID auth = UUID.randomUUID();
        jdbc.update("INSERT INTO system_objective(system_id,name) VALUES (?, 'migration')", system);
        jdbc.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)", cycle, system);
        jdbc.update("INSERT INTO intervention_plan(plan_id,cycle_id,status) VALUES (?,?,'PROPOSED')", plan, cycle);
        jdbc.update("INSERT INTO authorizations(authorization_id,requested_by,status,issued_at) "
                + "VALUES (?,'test','APPROVED',CURRENT_TIMESTAMP)", auth);
        for (String status : new String[]{"PENDING", "IN_PROGRESS", "COMPLETED", "FAILED", "UNKNOWN"}) {
            boolean pending = status.equals("PENDING"), running = status.equals("IN_PROGRESS");
            jdbc.update("""
                    INSERT INTO execution_attempt(execution_id,planned_action_id,plan_id,cycle_id,
                        authorization_id,attempt_number,execution_status,effect_outcome,actor_id,
                        started_at,completed_at)
                    VALUES (?,?,?,?,?,1,?,?,'test',?,?)
                    """, UUID.randomUUID(), UUID.randomUUID(), plan, cycle, auth, status,
                    status.equals("COMPLETED") ? "EFFECT_APPLIED" : status.equals("FAILED")
                            ? "NO_EFFECT_CONFIRMED" : "EFFECT_UNKNOWN",
                    pending ? null : java.sql.Timestamp.valueOf("2026-01-01 00:00:00"),
                    pending || running ? null : java.sql.Timestamp.valueOf("2026-01-01 00:01:00"));
        }
        var before = jdbc.queryForList("SELECT * FROM execution_attempt ORDER BY execution_id");
        assertEquals(1, Flyway.configure().dataSource(source).target("004").load().migrate().migrationsExecuted);
        var after = jdbc.queryForList("SELECT * FROM execution_attempt ORDER BY execution_id");
        for (int i = 0; i < before.size(); i++) {
            for (var entry : before.get(i).entrySet()) assertEquals(entry.getValue(), after.get(i).get(entry.getKey()));
            assertNull(after.get(i).get("EXECUTOR_ID"));
            assertNull(after.get(i).get("FENCING_TOKEN"));
        }
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE execution_attempt SET executor_id='invalid'"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        UPDATE execution_attempt SET executor_id='test', fencing_token=0,
                        heartbeat_at=started_at,lease_expires_at=DATEADD('SECOND',30,started_at)
                        WHERE execution_status='IN_PROGRESS'
                        """));
    }
}
