package com.miec.action;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Valida compatibilidad de V008 con operaciones registradas antes del snapshot. */
class ExecutionRequestMigrationTest {
    /** La actualización conserva claves y huellas históricas sin inventar cuerpos. */
    @Test @DisplayName("V008 conserva operaciones V007 sin fabricar su solicitud")
    void shouldPreserveLegacyOperationWithoutSnapshot() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:request_migration_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("007").load().migrate();
            var jdbc=new JdbcTemplate(source);var f=ExecutionRecoveryTestSupport.fixture(jdbc);var key=UUID.randomUUID();
            jdbc.update("INSERT INTO execution_operation(operation_key,plan_id,planned_action_id,destination_id,request_fingerprint,created_at) VALUES (?,?,?,'legacy',?,CURRENT_TIMESTAMP)",key,f.plan(),f.action(),"a".repeat(64));
            var before=jdbc.queryForMap("SELECT * FROM execution_operation WHERE operation_key=?",key);
            assertEquals(1,Flyway.configure().dataSource(source).target("008").load().migrate().migrationsExecuted);
            var after=jdbc.queryForMap("SELECT * FROM execution_operation WHERE operation_key=?",key);
            before.forEach((column,value)->assertEquals(value,after.get(column)));
            assertNull(after.get("REQUEST_BODY"));assertNull(after.get("REQUEST_FORMAT"));
        } finally { source.destroy(); }
    }
}
