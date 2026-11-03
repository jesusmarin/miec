package com.miec.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static com.miec.orchestration.MeasurementContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Valida el contrato medible con transacciones, migraciones y servicios reales. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:measurements;DB_CLOSE_DELAY=-1")
class MeasurementWorkflowTest {
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow workflow;
    @Autowired JdbcTemplate jdbc;
    @Autowired InterventionWorkflow intervention;
    /** Crea un ciclo E01 independiente por escenario. */
    private UUID started() {
        var system=cycles.createSystem(new LifecycleCommands.CreateSystem("medición",null,null,null));
        var cycle=cycles.createCycle(system.systemId(),new LifecycleCommands.CreateCycle(null),"author");
        cycles.startCycle(cycle.cycleId(),new LifecycleCommands.StartCycle(0L,"inicio"),"author"); return cycle.cycleId();
    }
    /** Criterio numérico que puede representar latencia u otra magnitud del proyecto. */
    private Criterion criterion(String name) { return new Criterion(name,"ms",Comparison.LTE,new BigDecimal("100.5")); }
    /** Objetivo y condición se consultan antes de crear diagnóstico o plan. */
    @Test void shouldPersistMeasurableObjectiveAndAuditTransition() {
        UUID id=started();
        var result=workflow.initialize(id,new Initialize(1L,"Reducir latencia","p95 <= 100.5 ms","objetivo",List.of(criterion("p95"))),"author");
        var context=workflow.context(id);
        assertEquals("MEASURABLE",context.mode()); assertEquals(result.resourceId(),context.objectives().getFirst().objectiveId());
        assertEquals(0,new BigDecimal("100.5").compareTo(context.objectives().getFirst().criteria().getFirst().target()));
        assertEquals("E02_OBSERVANDO",result.cycle().state()); assertEquals(2L,result.cycle().version());
        assertEquals(2,cycles.history(id,50,null).items().size());
    }
    /** Datos inválidos o repetidos no crean objetivos ni cambian versión o estado. */
    @Test void shouldRejectIncompleteOrDuplicateCriteriaAtomically() {
        UUID id=started();
        assertThrows(LifecycleException.class,()->workflow.initialize(id,new Initialize(1L,"o","c","r",List.of()),"author"));
        assertThrows(LifecycleException.class,()->workflow.initialize(id,new Initialize(1L,"o","c","r",List.of(criterion("p95"),criterion(" P95 "))),"author"));
        assertEquals(1L,cycles.getCycle(id).version()); assertTrue(workflow.context(id).objectives().isEmpty());
    }
    /** Conserva fechas, autor, fuente y precisión sin avanzar de etapa; rechaza unidades y referencias ajenas. */
    @Test void shouldRecordBaselineAndRejectInvalidMeasurements() {
        UUID id=started(); workflow.initialize(id,new Initialize(1L,"o","c","r",List.of(criterion("p95"))),"author");
        UUID indicator=workflow.context(id).objectives().getFirst().criteria().getFirst().indicatorId();
        var time=java.time.Instant.now().minusSeconds(60);
        assertThrows(LifecycleException.class,()->workflow.measure(id,new Measure(2L,UUID.randomUUID(),BigDecimal.ONE,"ms","sensor",time,"r"),"author"));
        assertThrows(LifecycleException.class,()->workflow.measure(id,new Measure(2L,indicator,BigDecimal.ONE,"s","sensor",time,"r"),"author"));
        assertThrows(LifecycleException.class,()->workflow.measure(id,new Measure(2L,indicator,BigDecimal.ONE,"ms","sensor",time.plusSeconds(3600),"r"),"author"));
        workflow.measure(id,new Measure(2L,indicator,new BigDecimal("125.12345"),"ms","sensor",time,"r"),"author");
        var row=workflow.context(id).baselines().getFirst();
        assertEquals("author",row.recordedBy()); assertEquals("sensor",row.source()); assertEquals("UNVERIFIED",row.qualityStatus());
        assertTrue(row.measuredAt().isBefore(row.recordedAt())); assertEquals(new BigDecimal("125.12345"),row.value());
        assertEquals("E02_OBSERVANDO",cycles.getCycle(id).state()); assertEquals(3L,cycles.getCycle(id).version());
        assertThrows(LifecycleException.class,()->workflow.measure(id,new Measure(2L,indicator,BigDecimal.ONE,"ms","sensor",time,"r"),"author"));
        assertEquals(1,workflow.context(id).baselines().size());
    }

    /** No cierra indicadores incompletos ni permite saltar el cierre por la API histórica. */
    @Test void shouldRequireAllBaselinesAndPreserveClosureContext() {
        UUID id=started(); workflow.initialize(id,new Initialize(1L,"o","c","r",List.of(criterion("a"),criterion("b"))),"author");
        assertThrows(LifecycleException.class,()->workflow.complete(id,new Complete(2L,"cerrar"),"author"));
        assertThrows(LifecycleException.class,()->intervention.observe(id,new InterventionCommands.Observe(2L,"dato",null,"r"),"author"));
        long version=2;
        for(var indicator:workflow.context(id).objectives().getFirst().criteria())
            workflow.measure(id,new Measure(version++,indicator.indicatorId(),BigDecimal.ONE,"ms","sensor",java.time.Instant.now().minusSeconds(1),"r"),"author");
        var closed=workflow.complete(id,new Complete(version,"cerrar"),"author");
        assertEquals("E03_DIAGNOSTICANDO",closed.cycle().state());
        var description=jdbc.queryForObject("SELECT description FROM evidence WHERE evidence_id=?",String.class,closed.resourceId());
        for(var baseline:workflow.context(id).baselines()) assertTrue(description.contains(baseline.baselineId().toString()));
        assertThrows(LifecycleException.class,()->workflow.complete(id,new Complete(closed.cycle().version(),"otra vez"),"author"));
        var diagnosed=intervention.diagnose(id,new InterventionCommands.Diagnose(closed.cycle().version(),closed.resourceId(),"diagnóstico","r"),"author");
        assertEquals("E04_PLANIFICANDO",diagnosed.cycle().state());
        var plan=intervention.propose(id,new InterventionCommands.ProposePlan(diagnosed.cycle().version(),workflow.context(id).objectives().getFirst().objectiveId(),
                "Mejorar latencia","Restaurar",List.of(new InterventionCommands.Action("PROCESS_EXECUTION","Validar"))),"author");
        var review=intervention.reviewContext(plan.planId());
        assertEquals(2,review.measurements().baselines().size()); assertEquals("MEASURABLE",review.measurements().mode());
        assertEquals(closed.resourceId(),review.diagnosis().evidenceId());
    }
    /** Dos inicializaciones simultáneas con la misma versión producen un único objetivo y transición. */
    @Test void shouldSerializeConcurrentInitialization() throws Exception {
        UUID id=started(); var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> task=()->{ gate.await(); try {
                workflow.initialize(id,new Initialize(1L,"o","c","r",List.of(criterion("p95"))),"author"); return true;
            } catch(LifecycleException expected) { assertEquals("VERSION_CONFLICT",expected.code()); return false; } };
            var a=pool.submit(task); var b=pool.submit(task); gate.countDown();
            assertNotEquals(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,workflow.context(id).objectives().size()); assertEquals(2L,cycles.getCycle(id).version());
    }

    /** V012 conserva datos V011 sin atribuir unidades, fuentes o condiciones que no existían. */
    @Test void shouldUpgradeV011WithoutInventingHistoricalMeasurements() {
        var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:h2:mem:measurement_upgrade_"+UUID.randomUUID(),"sa","",true);
        try {
            org.flywaydb.core.Flyway.configure().dataSource(source).target("011").load().migrate();
            var sql=new JdbcTemplate(source); UUID system=UUID.randomUUID(),cycle=UUID.randomUUID(),objective=UUID.randomUUID(),indicator=UUID.randomUUID(),baseline=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'legacy')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            sql.update("INSERT INTO objective(objective_id,system_id,name) VALUES (?,?,'legacy')",objective,system);
            sql.update("INSERT INTO indicator(indicator_id,objective_id,name) VALUES (?,?,'legacy')",indicator,objective);
            sql.update("INSERT INTO baseline(baseline_id,cycle_id,indicator_id,observed_value,measured_at) VALUES (?,?,?,12.5,CURRENT_TIMESTAMP)",baseline,cycle,indicator);
            var before=sql.queryForMap("SELECT * FROM baseline WHERE baseline_id=?",baseline);
            assertEquals(1,org.flywaydb.core.Flyway.configure().dataSource(source).target("012").load().migrate().migrationsExecuted);
            var after=sql.queryForMap("SELECT * FROM baseline WHERE baseline_id=?",baseline);
            before.forEach((key,value)->assertEquals(value,after.get(key)));
            assertNull(after.get("UNIT")); assertNull(after.get("RECORDED_BY"));
            assertNull(sql.queryForObject("SELECT comparison_operator FROM indicator WHERE indicator_id=?",String.class,indicator));
        } finally { source.destroy(); }
    }

    /** El reloj que se persiste tiene la misma precisión que TIMESTAMP(6), sin tolerancias temporales. */
    @Test void shouldUsePersistableMicrosecondClockPrecision() {
        assertEquals(0,cycles.now().getNano()%1000);
        UUID id=started(); workflow.initialize(id,new Initialize(1L,"o","c","r",List.of(criterion("p95"))),"author");
        assertEquals(0,cycles.getCycle(id).updatedAt().getNano()%1000);
    }

    /** Un fallo SQL al crear indicadores revierte también el objetivo, la transición y la versión. */
    @Test void shouldRollbackInitializationWhenIndicatorPersistenceFails() {
        UUID id=started();
        jdbc.execute("ALTER TABLE indicator ADD CONSTRAINT test_measurement_failure CHECK (name <> 'rollback-trigger')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->workflow.initialize(id,
                    new Initialize(1L,"o","c","r",List.of(criterion("valid"),criterion("rollback-trigger"))),"author"));
            assertEquals(1L,cycles.getCycle(id).version()); assertTrue(workflow.context(id).objectives().isEmpty());
            assertEquals(1,cycles.history(id,50,null).items().size());
        } finally { jdbc.execute("ALTER TABLE indicator DROP CONSTRAINT test_measurement_failure"); }
    }
    /** Dos cierres simultáneos crean una única evidencia de cierre y una transición a diagnóstico. */
    @Test void shouldSerializeConcurrentObservationClosure() throws Exception {
        UUID id=started(); workflow.initialize(id,new Initialize(1L,"o","c","r",List.of(criterion("p95"))),"author");
        var indicator=workflow.context(id).objectives().getFirst().criteria().getFirst().indicatorId();
        workflow.measure(id,new Measure(2L,indicator,BigDecimal.ONE,"ms","sensor",java.time.Instant.now().minusSeconds(1),"r"),"author");
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> task=()->{gate.await();try {workflow.complete(id,new Complete(3L,"cerrar"),"author");return true;}
                catch(LifecycleException expected){assertEquals("VERSION_CONFLICT",expected.code());return false;}};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();
            assertNotEquals(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM evidence WHERE cycle_id=? AND source_reference='MVP-MEASURE-CLOSURE'",Integer.class,id));
        assertEquals(4L,cycles.getCycle(id).version());
    }

}
