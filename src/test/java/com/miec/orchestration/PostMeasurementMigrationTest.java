package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Actualización real desde V014, conservación histórica y restricciones aditivas. */
@DisplayName("MVP-05.2 — migración aditiva de mediciones posteriores")
class PostMeasurementMigrationTest {
    /** Conserva datos previos byte a byte en sus columnas sin crear mediciones o revisiones ficticias. */
    @Test @DisplayName("V015 conserva línea base y evidencia históricas sin inventar resultados")
    void shouldPreserveV014History() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:post_upgrade_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("014").load().migrate();var sql=new JdbcTemplate(source);
            UUID system=UUID.randomUUID(),cycle=UUID.randomUUID(),objective=UUID.randomUUID(),indicator=UUID.randomUUID(),baseline=UUID.randomUUID(),evidence=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'legacy')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            sql.update("INSERT INTO objective(objective_id,system_id,name) VALUES (?,?,'historico')",objective,system);
            sql.update("INSERT INTO indicator(indicator_id,objective_id,name) VALUES (?,?,'indicador')",indicator,objective);
            sql.update("INSERT INTO evidence(evidence_id,cycle_id,source_type,captured_at,quality_status) VALUES (?,?,'HUMAN_REPORT',CURRENT_TIMESTAMP,'UNVERIFIED')",evidence,cycle);
            sql.update("INSERT INTO baseline(baseline_id,cycle_id,indicator_id,observed_value,measured_at,evidence_id) VALUES (?,?,?,12.34567,CURRENT_TIMESTAMP,?)",baseline,cycle,indicator,evidence);
            var before=sql.queryForMap("SELECT * FROM baseline WHERE baseline_id=?",baseline);
            var proof=sql.queryForMap("SELECT * FROM evidence WHERE evidence_id=?",evidence);
            assertEquals(1,Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
            assertEquals(before,sql.queryForMap("SELECT * FROM baseline WHERE baseline_id=?",baseline));
            assertEquals(proof,sql.queryForMap("SELECT * FROM evidence WHERE evidence_id=?",evidence));
            for(String table:new String[]{"post_measurement","measurement_review","measurement_set","measurement_pair"})
                assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("""
                    INSERT INTO measurement_review VALUES (?,?,?,?,'REJECTED','reviewer','metodo','acta','limitacion',CURRENT_TIMESTAMP)
                    """,UUID.randomUUID(),UUID.randomUUID(),cycle,evidence));
        } finally {source.destroy();}
    }
}
