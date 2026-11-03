package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Verifica que el aislamiento no invente rondas para historia sin contexto acreditado. */
@DisplayName("MVP-07.4 — migración de aislamiento V019")
class RoundIsolationMigrationTest {
    /** Conserva los datos previos y deja sin asignación el contexto desconocido. */
    @Test @DisplayName("V018 a V019 conserva filas sin asignarles una ronda")
    void preservesUnassignedHistory(){
        var source=new SingleConnectionDataSource("jdbc:h2:mem:round_isolation_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("018").load().migrate();var sql=new JdbcTemplate(source);
            UUID system=UUID.randomUUID(),cycle=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'historia')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            var before=sql.queryForMap("SELECT * FROM cycle WHERE cycle_id=?",cycle);
            assertEquals(1,Flyway.configure().dataSource(source).target("019").load().migrate().migrationsExecuted);
            assertEquals(before,sql.queryForMap("SELECT * FROM cycle WHERE cycle_id=?",cycle));
            for(String table:new String[]{"round_baseline","round_observation","round_diagnosis","round_plan_binding"})
                assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        } finally {source.destroy();}
    }
}
