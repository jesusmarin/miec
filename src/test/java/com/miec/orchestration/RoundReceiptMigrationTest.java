package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actualización con recibos reales de esquema V020 y protección del contexto sin ronda. */
@DisplayName("MVP-07.7 — migración de recibos por ronda V021")
class RoundReceiptMigrationTest {
    /** Conserva todos los valores anteriores y reemplaza la unicidad sin liberar el contexto legado. */
    @Test @DisplayName("V021 preserva admisión y decisión legadas y mantiene una por ciclo sin ronda")
    void preservesLegacyReceipts(){
        var source=new SingleConnectionDataSource("jdbc:h2:mem:round_receipts_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("020").load().migrate();var sql=new JdbcTemplate(source);
            UUID system=UUID.randomUUID(),cycle=UUID.randomUUID(),objective=UUID.randomUUID(),evaluation=UUID.randomUUID(),decision=UUID.randomUUID(),event=UUID.randomUUID(),choice=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'historia')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            sql.update("INSERT INTO objective(objective_id,system_id,name) VALUES (?,?,'objetivo legado')",objective,system);
            sql.update("INSERT INTO cycle_objective(cycle_id,objective_id,system_id) VALUES (?,?,?)",cycle,objective,system);
            sql.update("""
                INSERT INTO evaluation(evaluation_id,cycle_id,objective_id,criteria_version_id,result_classification,evidence_sufficiency,confidence_level,evaluated_at)
                VALUES (?,?,?,'legacy','INCONCLUSIVE','EV_0','LOW',CURRENT_TIMESTAMP)
                """,evaluation,cycle,objective);
            for(UUID id:List.of(event,choice))sql.update("INSERT INTO cycle_event(event_id,cycle_id,event_type,occurred_at) VALUES (?,?,'LEGACY',CURRENT_TIMESTAMP)",id,cycle);
            sql.update("INSERT INTO evaluation_admission VALUES (?,?,?,0,'author','historia',CURRENT_TIMESTAMP)",evaluation,cycle,event);
            sql.update("INSERT INTO decision_record(decision_id,cycle_id,evaluation_id,decision_type,decision_status) VALUES (?,?,?,'legacy','RECORDED')",decision,cycle,evaluation);
            sql.update("INSERT INTO human_decision VALUES (?,?,?,'COLLECT_EVIDENCE','approver','historia',TRUE,'F02-MVP-V1',?,CURRENT_TIMESTAMP)",decision,evaluation,cycle,choice);
            var admission=sql.queryForMap("SELECT * FROM evaluation_admission");var human=sql.queryForMap("SELECT * FROM human_decision");
            assertEquals(1,Flyway.configure().dataSource(source).target("021").load().migrate().migrationsExecuted);
            var after=sql.queryForMap("SELECT * FROM evaluation_admission");var decisionAfter=sql.queryForMap("SELECT * FROM human_decision");
            admission.forEach((key,value)->assertEquals(value,after.get(key)));human.forEach((key,value)->assertEquals(value,decisionAfter.get(key)));
            assertNull(after.get("ROUND_ID"));assertEquals(cycle,after.get("LEGACY_CYCLE_ID"));assertEquals(cycle,decisionAfter.get("LEGACY_CYCLE_ID"));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM cycle_round",Integer.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE evaluation_admission SET legacy_cycle_id=NULL"));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE human_decision SET round_id=?",UUID.randomUUID()));
            UUID other=UUID.randomUUID(),otherEvent=UUID.randomUUID();
            sql.update("INSERT INTO evaluation(evaluation_id,cycle_id,objective_id,criteria_version_id,result_classification,evidence_sufficiency,confidence_level,evaluated_at) VALUES (?,?,?,'legacy','INCONCLUSIVE','EV_0','LOW',CURRENT_TIMESTAMP)",other,cycle,objective);
            sql.update("INSERT INTO cycle_event(event_id,cycle_id,event_type,occurred_at) VALUES (?,?,'LEGACY',CURRENT_TIMESTAMP)",otherEvent,cycle);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("INSERT INTO evaluation_admission VALUES (?,?,?,0,'author','duplicado',CURRENT_TIMESTAMP,NULL,?)",other,cycle,otherEvent,cycle));
            assertTrue(Flyway.configure().dataSource(source).load().validateWithResult().validationSuccessful);
        } finally {source.destroy();}
    }
}
