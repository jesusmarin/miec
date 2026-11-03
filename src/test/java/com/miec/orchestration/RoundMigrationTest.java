package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Verifica actualización real desde V017 sin inventar contexto histórico. */
@DisplayName("MVP-07.2 — migración aditiva V018")
class RoundMigrationTest {
    /** Conserva filas anteriores y exige integridad al intentar asignar referencias ajenas. */
    @Test @DisplayName("V018 conserva historia y no asigna rondas automáticamente")
    void preservesHistory(){
        var source=new SingleConnectionDataSource("jdbc:h2:mem:round_upgrade_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("017").load().migrate();var sql=new JdbcTemplate(source);
            UUID system=UUID.randomUUID(),cycle=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'historia')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            var before=sql.queryForMap("SELECT * FROM cycle WHERE cycle_id=?",cycle);
            assertEquals(1,Flyway.configure().dataSource(source).target("018").load().migrate().migrationsExecuted);
            assertEquals(before,sql.queryForMap("SELECT * FROM cycle WHERE cycle_id=?",cycle));
            for(String table:new String[]{"cycle_round","round_context","plan_completion"})
                assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update(
                "INSERT INTO plan_completion VALUES (?,?,?,0,0,'approver','motivo','PLAN-COMPLETION-V1',?,CURRENT_TIMESTAMP)",UUID.randomUUID(),UUID.randomUUID(),cycle,UUID.randomUUID()));
        } finally {source.destroy();}
    }
}
