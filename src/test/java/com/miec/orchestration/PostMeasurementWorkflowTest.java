package com.miec.orchestration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.miec.orchestration.PostMeasurementTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Registro posterior real, auditoría atómica y conservación del significado de línea base. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:post_measure_workflow;DB_CLOSE_DELAY=-1")
@DisplayName("MVP-05.3 — registro transaccional de resultados posteriores")
class PostMeasurementWorkflowTest {
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow workflow;
    @Autowired JdbcTemplate jdbc;
    /** Elimina la identidad simulada después de cada escenario. */
    @AfterEach void cleanup() {SecurityContextHolder.clearContext();}
    /** Prepara un contexto independiente mediante el flujo de negocio. */
    private Fixture fixture() {return ready(cycles,measures,intervention,execution);}

    /** Conserva decimal, origen, método, criterio, recepción y evidencia sin evaluar o alterar baseline. */
    @Test @DisplayName("Registra resultado UNVERIFIED y conserva E08 y línea base")
    void shouldRecordImmutableFactWithAudit() {
        var f=fixture();var before=measures.context(f.cycleId());var version=cycles.getCycle(f.cycleId()).version();var command=command(f,version);
        var context=workflow.record(f.cycleId(),f.planId(),command,"author");var post=context.measurements().getFirst();
        assertEquals(command.measurement().value(),post.value());assertEquals("author",post.recordedBy());assertEquals("UNVERIFIED",post.qualityStatus());
        assertEquals(command.method(),post.method());assertEquals(command.measurement().measuredAt(),post.measuredAt());assertFalse(post.recordedAt().isBefore(post.measuredAt()));
        assertEquals("MVP-MEASURE-01",post.criterionVersion());assertEquals(MeasurementContracts.Comparison.LTE,post.operator());
        assertEquals(version+1,context.cycleVersion());assertEquals("E08_VERIFICANDO",cycles.getCycle(f.cycleId()).state());assertEquals(before,measures.context(f.cycleId()));
        assertNull(context.set());assertTrue(context.reviews().isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=? AND event_type='POST_MEASUREMENT_RECORDED'",Integer.class,f.cycleId()));
        assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),command,"author"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM post_measurement WHERE run_id=?",Integer.class,f.runId()));
    }

    /** Una restricción real en el evento fuerza rollback de dato, evidencia y versión. */
    @Test @DisplayName("Revierte registro completo cuando falla la auditoría")
    void shouldRollbackPostAndEvidenceWhenEventFails() {
        var f=fixture();long version=cycles.getCycle(f.cycleId()).version();
        int proofs=jdbc.queryForObject("SELECT COUNT(*) FROM evidence WHERE cycle_id=?",Integer.class,f.cycleId());
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT test_post_audit CHECK(cycle_id<>CAST('"+f.cycleId()+"' AS UUID) OR event_type<>'POST_MEASUREMENT_RECORDED')");
        try {
            assertThrows(RuntimeException.class,()->workflow.record(f.cycleId(),f.planId(),command(f,version),"author"));
            assertEquals(version,cycles.getCycle(f.cycleId()).version());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM post_measurement WHERE run_id=?",Integer.class,f.runId()));
            assertEquals(proofs,jdbc.queryForObject("SELECT COUNT(*) FROM evidence WHERE cycle_id=?",Integer.class,f.cycleId()));
        } finally {jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT test_post_audit");}
    }

    /** Las referencias deben corresponder simultáneamente al ciclo, plan, run e indicador aprobado. */
    @Test @DisplayName("Rechaza referencias ajenas y unidades incompatibles")
    void shouldRejectForeignReferencesAndUnits() {
        var f=fixture();var other=fixture();long version=cycles.getCycle(f.cycleId()).version();var valid=command(f,version);var measure=valid.measurement();
        assertEquals("PLAN_CYCLE_MISMATCH",assertThrows(LifecycleException.class,()->workflow.record(other.cycleId(),f.planId(),valid,"author")).code());
        assertEquals("RUN_CONTEXT_MISMATCH",assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),new MeasurementContracts.PostMeasure(other.runId(),measure,valid.method()),"author")).code());
        var foreign=new MeasurementContracts.Measure(version,other.indicatorId(),measure.value(),"ms","fuente",measure.measuredAt(),"razón");
        assertEquals("INVALID_INDICATOR",assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),new MeasurementContracts.PostMeasure(f.runId(),foreign,valid.method()),"author")).code());
        var wrongUnit=new MeasurementContracts.Measure(version,f.indicatorId(),measure.value(),"s","fuente",measure.measuredAt(),"razón");
        assertEquals("UNIT_MISMATCH",assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),new MeasurementContracts.PostMeasure(f.runId(),wrongUnit,valid.method()),"author")).code());
        assertEquals(version,cycles.getCycle(f.cycleId()).version());
    }

    /** Un indicador del mismo ciclo pero de otro objetivo tampoco satisface el contenido del plan. */
    @Test @DisplayName("Restringe el indicador al objetivo aprobado")
    void shouldRejectAnotherObjectiveInSameCycle() {
        var f=fixture();var objective=java.util.UUID.randomUUID();var indicator=java.util.UUID.randomUUID();var system=cycles.getCycle(f.cycleId()).systemId();
        jdbc.update("INSERT INTO objective(objective_id,system_id,name) VALUES (?,?,'otro')",objective,system);
        jdbc.update("INSERT INTO cycle_objective(cycle_id,objective_id,system_id) VALUES (?,?,?)",f.cycleId(),objective,system);
        jdbc.update("INSERT INTO indicator(indicator_id,objective_id,name,unit,comparison_operator,acceptance_threshold,version_id) VALUES (?,?,'otro','ms','LTE',100,'test')",indicator,objective);
        var measure=new MeasurementContracts.Measure(cycles.getCycle(f.cycleId()).version(),indicator,java.math.BigDecimal.ONE,"ms","fuente",now(),"razón");
        assertEquals("INDICATOR_PLAN_MISMATCH",assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),new MeasurementContracts.PostMeasure(f.runId(),measure,"método"),"author")).code());
    }

    /** La fecha posterior no puede preceder la finalización, superar recepción ni redondearse silenciosamente. */
    @Test @DisplayName("Rechaza fechas ajenas a la ejecución y precisión temporal no representable")
    void shouldRejectInvalidDatesAndPrecision() {
        var f=fixture();long version=cycles.getCycle(f.cycleId()).version();
        for(var date:java.util.List.of(f.baselineAt(),now().plusSeconds(60),now().plusNanos(1))) {
            var measure=new MeasurementContracts.Measure(version,f.indicatorId(),java.math.BigDecimal.ONE,"ms","fuente",date,"razón");
            assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),new MeasurementContracts.PostMeasure(f.runId(),measure,"método"),"author"));
        }
        var tooPrecise=new MeasurementContracts.Measure(version,f.indicatorId(),new java.math.BigDecimal("1.000001"),"ms","fuente",now(),"razón");
        assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),new MeasurementContracts.PostMeasure(f.runId(),tooPrecise,"método"),"author"));
        assertEquals(version,cycles.getCycle(f.cycleId()).version());
    }
}
