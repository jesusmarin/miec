package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.UUID;
import static com.miec.orchestration.InterventionCommands.*;
import static org.junit.jupiter.api.Assertions.*;

/** Preparación real con etapas, referencias y auditoría en una base independiente. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:intervention_preparation;DB_CLOSE_DELAY=-1")
class InterventionPreparationTest {
    @Autowired private LifecycleService cycles;
    @Autowired private InterventionWorkflow workflow;
    @Autowired private JdbcTemplate jdbc;

    /** Crea un ciclo iniciado por el flujo MVP-01. */
    private LifecycleViews.CycleView started() {
        var system=cycles.createSystem(new LifecycleCommands.CreateSystem("preparación",null,null,null));
        var cycle=cycles.createCycle(system.systemId(),new LifecycleCommands.CreateCycle(null),"author");
        return cycles.startCycle(cycle.cycleId(),new LifecycleCommands.StartCycle(0L,"iniciar"),"author");
    }

    /** El recorrido conserva objetivo, evidencia no verificada y diagnóstico ligados al ciclo. */
    @Test void shouldPrepareCycleWithoutSkippingStatesOrInventingVerifiedEvidence() {
        var cycle=started();
        var initialized=workflow.initialize(cycle.cycleId(),new Initialize(1L,"Reducir fallos","Cero fallos en la prueba","objetivo definido"),"author");
        assertEquals("E02_OBSERVANDO",initialized.cycle().state());
        var observed=workflow.observe(cycle.cycleId(),new Observe(2L,"Se observan fallos",null,"observación"),"author");
        assertEquals("UNVERIFIED",jdbc.queryForObject("SELECT quality_status FROM evidence WHERE evidence_id=?",String.class,observed.resourceId()));
        var diagnosed=workflow.diagnose(cycle.cycleId(),new Diagnose(3L,observed.resourceId(),"Revisar configuración","diagnóstico humano"),"author");
        assertEquals("E04_PLANIFICANDO",diagnosed.cycle().state());
        assertEquals(4,cycles.history(cycle.cycleId(),50,null).items().size());
        assertEquals(initialized.resourceId(),jdbc.queryForObject("SELECT objective_id FROM cycle_objective WHERE cycle_id=?",UUID.class,cycle.cycleId()));
    }

    /** Una versión vieja, un salto o datos incompletos no cambian el ciclo. */
    @Test void shouldRejectInvalidPreparation() {
        var cycle=started();
        assertThrows(LifecycleException.class,()->workflow.observe(cycle.cycleId(),new Observe(1L,"dato",null,"motivo"),"author"));
        assertThrows(LifecycleException.class,()->workflow.initialize(cycle.cycleId(),new Initialize(0L,"o","criterio","motivo"),"author"));
        assertThrows(LifecycleException.class,()->workflow.initialize(cycle.cycleId(),new Initialize(1L," ","criterio","motivo"),"author"));
        assertEquals(cycle,cycles.getCycle(cycle.cycleId()));
    }

    /** El diagnóstico no admite una evidencia desconocida ni deja escrituras parciales. */
    @Test void shouldRejectForeignEvidence() {
        var cycle=started();
        workflow.initialize(cycle.cycleId(),new Initialize(1L,"o","criterio","motivo"),"author");
        workflow.observe(cycle.cycleId(),new Observe(2L,"dato",null,"motivo"),"author");
        assertThrows(LifecycleException.class,()->workflow.diagnose(cycle.cycleId(),new Diagnose(3L,UUID.randomUUID(),"diagnóstico","motivo"),"author"));
        assertEquals("E03_DIAGNOSTICANDO",cycles.getCycle(cycle.cycleId()).state());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cycle_diagnosis WHERE cycle_id=?",Integer.class,cycle.cycleId()));
    }

    /** V011 amplía una base V010 sin fabricar creador ni cambiar revisiones históricas. */
    @Test void shouldUpgradeExistingDatabase() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:mvp02_migration_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("010").load().migrate();
            var sql=new JdbcTemplate(source); UUID system=UUID.randomUUID(),cycle=UUID.randomUUID(),plan=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'legacy')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            sql.update("INSERT INTO intervention_plan(plan_id,cycle_id,status) VALUES (?,?,'PROPOSED')",plan,cycle);
            var before=sql.queryForMap("SELECT * FROM intervention_plan WHERE plan_id=?",plan);
            assertEquals(1,Flyway.configure().dataSource(source).target("011").load().migrate().migrationsExecuted);
            var after=sql.queryForMap("SELECT * FROM intervention_plan WHERE plan_id=?",plan);
            before.forEach((key,value)->assertEquals(value,after.get(key)));
            assertNull(after.get("CREATED_BY")); assertEquals(0L,after.get("REVISION"));
        } finally { source.destroy(); }
    }
}
