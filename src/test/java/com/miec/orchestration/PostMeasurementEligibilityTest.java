package com.miec.orchestration;

import com.miec.action.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.concurrent.*;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.PostMeasurementTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Guardas E08, contradicciones posteriores y serialización real de muestras frente al cierre. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:post_measure_eligibility;DB_CLOSE_DELAY=-1")
@DisplayName("MVP-05.7 — elegibilidad E08 y concurrencia")
class PostMeasurementEligibilityTest {
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow workflow;
    @Autowired MeasurementApplicationService application;
    @Autowired MeasurementQueries queries;
    @Autowired ExecutionTestConfiguration.TestAdapter adapter;
    @Autowired ExecutionAttemptService attempts;
    @Autowired ExecutionEvidenceService evidence;
    /** Restaura hooks y seguridad incluso ante errores en escenarios concurrentes. */
    @AfterEach void cleanup() {SecurityContextHolder.clearContext();adapter.afterEffect=()->{};}

    /** No confunde una aplicación técnica ni un resultado incierto con elegibilidad para medir en E08. */
    @Test @DisplayName("Bloquea registro en E07 y E13 sin finalizar el ciclo")
    void shouldRejectExecutingAndRecoveringCycles() {
        for(boolean lost:List.of(false,true)) {
            adapter.afterEffect=lost?()->{throw new IllegalStateException("respuesta perdida");}:()->{};
            var f=executed(cycles,measures,intervention,execution,false);
            assertEquals(lost?"E13_RECUPERANDO":"E07_EJECUTANDO",cycles.getCycle(f.cycleId()).state());
            long version=cycles.getCycle(f.cycleId()).version();
            assertEquals("TRANSITION_NOT_ALLOWED",assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),command(f,version),"author")).code());
            assertTrue(queries.posts(f.cycleId(),f.planId()).measurements().isEmpty());assertEquals(version,cycles.getCycle(f.cycleId()).version());
        }
    }

    /** Una contradicción posterior al evento de finalización vuelve a bloquear la admisión de resultados. */
    @Test @DisplayName("Bloquea mediciones si IR-009 recibe evidencia contradictoria después de E08")
    void shouldRejectLateTechnicalConflict() {
        var f=ready(cycles,measures,intervention,execution);var action=execution.get(f.planId()).steps().getFirst().actionId();
        var attempt=attempts.findHistory(f.planId(),action).getFirst();
        evidence.report(UUID.randomUUID(),attempt.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradicción",now());
        assertEquals("EXECUTION_NOT_CONFIRMED",assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),command(f,cycles.getCycle(f.cycleId()).version()),"author")).code());
        assertEquals("E08_VERIFICANDO",cycles.getCycle(f.cycleId()).state());assertTrue(execution.get(f.planId()).requiresReview());
    }

    /** Un plan descriptivo aprobado no recibe indicadores fabricados aunque se haya ejecutado un efecto técnico. */
    @Test @DisplayName("Conserva compatibilidad histórica sin inventar criterios medibles")
    void shouldRejectLegacyUnstructuredMeasurement() {
        authenticate("AUTHOR");var plan=PlanExecutionTestSupport.approved(cycles,intervention,1,true);
        var run=execution.start(plan.cycleId(),plan.planId(),new PlanExecutionContracts.Start(cycles.getCycle(plan.cycleId()).version(),plan.version(),"ejecutar"));
        authenticate("APPROVER");execution.finish(plan.planId(),new PlanExecutionContracts.Finish(run.version(),cycles.getCycle(plan.cycleId()).version(),"finalizar"));
        var measure=new Measure(cycles.getCycle(plan.cycleId()).version(),UUID.randomUUID(),java.math.BigDecimal.ONE,"ms","origen",now(),"reporte");
        assertEquals("INVALID_INDICATOR",assertThrows(LifecycleException.class,()->workflow.record(plan.cycleId(),plan.planId(),new PostMeasure(run.runId(),measure,"método"),"author")).code());
    }

    /** Comandos concurrentes con la misma revisión no amplían silenciosamente un conjunto que se está cerrando. */
    @Test @DisplayName("Solo registro o cierre confirma ante concurrencia sobre la misma versión")
    void shouldSerializeRecordingAgainstSealing() throws Exception {
        var f=ready(cycles,measures,intervention,execution);var posts=workflow.record(f.cycleId(),f.planId(),command(f,cycles.getCycle(f.cycleId()).version()),"author");
        var post=posts.measurements().getFirst();var record=command(f,posts.cycleVersion());
        var seal=new SealPost(posts.cycleVersion(),f.runId(),f.baselineAt().minusSeconds(1),f.baselineAt().plusSeconds(1),post.measuredAt(),now(),post.method(),
                List.of(new PairSelection(f.indicatorId(),f.baselineId(),post.measurementId())),"cierre");
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> write=()->{gate.await();try{workflow.record(f.cycleId(),f.planId(),record,"author");return true;}
                catch(LifecycleException conflict){assertEquals("VERSION_CONFLICT",conflict.code());return false;}};
            Callable<Boolean> close=()->{gate.await();try{workflow.seal(f.cycleId(),f.planId(),seal,"approver");return true;}
                catch(LifecycleException conflict){assertEquals("VERSION_CONFLICT",conflict.code());return false;}};
            var first=pool.submit(write);var second=pool.submit(close);gate.countDown();
            boolean recorded=first.get(15,TimeUnit.SECONDS),sealed=second.get(15,TimeUnit.SECONDS);assertNotEquals(recorded,sealed);
            var current=queries.posts(f.cycleId(),f.planId());assertEquals(posts.cycleVersion()+1,current.cycleVersion());
            assertEquals(recorded?2:1,current.measurements().size());assertEquals(sealed,current.set()!=null);
        }
    }

    /** Los permisos de la fachada son efectivos también sin atravesar un controlador HTTP. */
    @Test @DisplayName("La fachada exige identidad y rol incluso fuera de HTTP")
    void shouldEnforceApplicationRoles() {
        var f=ready(cycles,measures,intervention,execution);var command=command(f,cycles.getCycle(f.cycleId()).version());
        authenticate("APPROVER");assertThrows(LifecycleException.class,()->application.recordPost(f.cycleId(),f.planId(),command));
        authenticate("AUTHOR");assertThrows(LifecycleException.class,()->application.reviewPost(f.cycleId(),f.planId(),new ReviewMeasure(0L,f.runId(),UUID.randomUUID(),ReviewVerdict.REJECTED,"m","p","l","r")));
        SecurityContextHolder.clearContext();assertThrows(LifecycleException.class,()->application.posts(f.cycleId(),f.planId()));
    }
}
