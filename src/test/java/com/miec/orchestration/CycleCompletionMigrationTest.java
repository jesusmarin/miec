package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Evolución aditiva de recibos operativos, recomendaciones y aprendizaje histórico. */
@DisplayName("MVP-06 — migración de cierre V017")
class CycleCompletionMigrationTest {
    /** Conserva decisiones y aprendizaje anteriores, sin fabricar recibos o cierres. */
    @Test @DisplayName("V016 a V017 conserva la historia y valida extensiones completas")
    void shouldPreserveHistoricalDecisionsAndLearning() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:closure_upgrade_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("016").load().migrate();var sql=new JdbcTemplate(source);
            UUID system=UUID.randomUUID(),cycle=UUID.randomUUID(),decision=UUID.randomUUID(),learning=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'legacy')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            sql.update("INSERT INTO decision_record(decision_id,cycle_id,decision_type,decision_status) VALUES (?,?,'legacy','RECORDED')",decision,cycle);
            sql.update("INSERT INTO learning(learning_id,cycle_id,statement,status) VALUES (?,?,'historia','RECORDED')",learning,cycle);
            var before=sql.queryForMap("SELECT * FROM decision_record WHERE decision_id=?",decision);var oldLearning=sql.queryForMap("SELECT * FROM learning WHERE learning_id=?",learning);
            assertEquals(1,Flyway.configure().dataSource(source).target("017").load().migrate().migrationsExecuted);
            var after=sql.queryForMap("SELECT * FROM decision_record WHERE decision_id=?",decision);var newLearning=sql.queryForMap("SELECT * FROM learning WHERE learning_id=?",learning);
            before.forEach((key,value)->assertEquals(value,after.get(key)));oldLearning.forEach((key,value)->assertEquals(value,newLearning.get(key)));
            assertNull(after.get("OUTCOME_SNAPSHOT"));assertNull(newLearning.get("DECISION_ID"));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM cycle_closure",Integer.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE learning SET recorded_by='partial' WHERE learning_id=?",learning));
        } finally {source.destroy();}
    }
}
