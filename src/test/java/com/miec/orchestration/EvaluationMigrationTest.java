package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** V016 conserva evaluaciones históricas sin fabricar conjuntos ni conclusiones nuevas. */
@DisplayName("MVP-06.3 — evolución aditiva de evaluación")
class EvaluationMigrationTest {
    /** Actualiza V015 y comprueba cada columna anterior y la nulidad de la extensión histórica. */
    @Test @DisplayName("V015 a V016 conserva conclusiones históricas y exige extensión coherente")
    void shouldPreserveHistoricalEvaluations() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:eval_upgrade_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("015").load().migrate();var sql=new JdbcTemplate(source);
            UUID system=UUID.randomUUID(),cycle=UUID.randomUUID(),objective=UUID.randomUUID(),evaluation=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'legacy')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            sql.update("INSERT INTO objective(objective_id,system_id,name) VALUES (?,?,'legacy')",objective,system);
            sql.update("INSERT INTO cycle_objective(cycle_id,objective_id,system_id) VALUES (?,?,?)",cycle,objective,system);
            sql.update("""
                    INSERT INTO evaluation(evaluation_id,cycle_id,objective_id,criteria_version_id,result_classification,evidence_sufficiency,confidence_level,evaluated_at)
                    VALUES (?,?,?,'legacy','INCONCLUSIVE','INSUFFICIENT','LOW',CURRENT_TIMESTAMP)
                    """,evaluation,cycle,objective);
            var before=sql.queryForMap("SELECT * FROM evaluation WHERE evaluation_id=?",evaluation);
            assertEquals(1,Flyway.configure().dataSource(source).target("016").load().migrate().migrationsExecuted);
            var after=sql.queryForMap("SELECT * FROM evaluation WHERE evaluation_id=?",evaluation);
            before.forEach((key,value)->assertEquals(value,after.get(key),key));
            assertNull(after.get("MEASUREMENT_SET_ID"));assertNull(after.get("EVALUATION_SNAPSHOT"));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE evaluation SET recorded_by='partial' WHERE evaluation_id=?",evaluation));
        } finally {source.destroy();}
    }
}
