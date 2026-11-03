package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** V010 amplía la auditoría sin inventar motivos de eventos previos ni cambiar sus datos. */
class LifecycleMigrationTest {
    /** Actualizar una base V009 conserva el evento y permite un motivo auditable nuevo. */
    @Test void shouldPreserveExistingEventsWhenMigrating() {
        var source = new SingleConnectionDataSource("jdbc:h2:mem:lifecycle_migration_" + UUID.randomUUID(), "sa", "", true);
        try {
            Flyway.configure().dataSource(source).target("009").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID system = UUID.randomUUID(), cycle = UUID.randomUUID(), event = UUID.randomUUID();
            jdbc.update("INSERT INTO system_objective(system_id,name) VALUES (?,'legacy')", system);
            jdbc.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)", cycle, system);
            jdbc.update("INSERT INTO cycle_event(event_id,cycle_id,event_type,occurred_at) VALUES (?,?,'CYCLE_CREATED',CURRENT_TIMESTAMP)", event, cycle);
            var before = jdbc.queryForMap("SELECT * FROM cycle_event WHERE event_id=?", event);
            assertEquals(1, Flyway.configure().dataSource(source).target("010").load().migrate().migrationsExecuted);
            var after = jdbc.queryForMap("SELECT * FROM cycle_event WHERE event_id=?", event);
            before.forEach((key, value) -> assertEquals(value, after.get(key)));
            assertNull(after.get("REASON"));
            assertEquals(1, jdbc.update("UPDATE cycle_event SET reason='motivo' WHERE event_id=?", event));
        } finally { source.destroy(); }
    }
}
