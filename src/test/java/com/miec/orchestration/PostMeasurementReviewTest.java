package com.miec.orchestration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.PostMeasurementTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Revisiones humanas separadas y selección reproducible, sin equivalencias automáticas con EV o éxito. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:post_measure_review;DB_CLOSE_DELAY=-1")
@DisplayName("MVP-05.5 — revisión y conjunto inmutable de comparación")
class PostMeasurementReviewTest {
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow workflow;
    @Autowired MeasurementQueries queries;
    @Autowired JdbcTemplate jdbc;
    /** Libera identidad instalada al preparar el fixture. */
    @AfterEach void cleanup() {SecurityContextHolder.clearContext();}
    /** Contexto nuevo con una medición posterior. */
    private Fixture fixture() {
        var f=ready(cycles,measures,intervention,execution);workflow.record(f.cycleId(),f.planId(),command(f,cycles.getCycle(f.cycleId()).version()),"author");return f;
    }
    /** Revisión manual con limitaciones obligatorias, sin nivel EV elegido por el cliente. */
    private ReviewMeasure review(Fixture f,UUID evidence) {
        return new ReviewMeasure(cycles.getCycle(f.cycleId()).version(),f.runId(),evidence,ReviewVerdict.ACCEPTED_WITH_LIMITATIONS,
                "procedimiento p95 v1","acta de contraste","Revisión humana; no demuestra causalidad","revisar");
    }
    /** Ventanas y par explícito que incluye la muestra seleccionada. */
    private SealPost seal(Fixture f) {
        var post=queries.posts(f.cycleId(),f.planId()).measurements().getFirst();
        return new SealPost(cycles.getCycle(f.cycleId()).version(),f.runId(),f.baselineAt().minusSeconds(1),f.baselineAt().plusSeconds(1),
                post.measuredAt(),now(),post.method(),List.of(new PairSelection(f.indicatorId(),f.baselineId(),post.measurementId())),"fijar muestras");
    }

    /** No cambia la evidencia original ni permite revisión por autor o una segunda revisión sobre el mismo hecho. */
    @Test @DisplayName("Revisión separada, sin autoevaluación, autorevisión ni sobrescritura")
    void shouldKeepReviewSeparateAndUnique() {
        var f=fixture();var post=queries.posts(f.cycleId(),f.planId()).measurements().getFirst();var raw=jdbc.queryForMap("SELECT * FROM evidence WHERE evidence_id=?",post.evidenceId());
        assertEquals("SELF_REVIEW_FORBIDDEN",assertThrows(LifecycleException.class,()->workflow.review(f.cycleId(),f.planId(),review(f,post.evidenceId()),"author")).code());
        var context=workflow.review(f.cycleId(),f.planId(),review(f,post.evidenceId()),"approver");
        assertEquals(ReviewVerdict.ACCEPTED_WITH_LIMITATIONS,context.reviews().getFirst().verdict());assertEquals("approver",context.reviews().getFirst().reviewedBy());
        assertEquals(raw,jdbc.queryForMap("SELECT * FROM evidence WHERE evidence_id=?",post.evidenceId()));assertEquals("UNVERIFIED",context.measurements().getFirst().qualityStatus());
        assertEquals("MEASUREMENT_ALREADY_REVIEWED",assertThrows(LifecycleException.class,()->workflow.review(f.cycleId(),f.planId(),review(f,post.evidenceId()),"approver")).code());
    }

    /** El cierre conserva IDs de revisiones y bloquea toda ampliación posterior del mismo run. */
    @Test @DisplayName("Conjunto cerrado conserva muestras y revisiones sin declarar éxito")
    void shouldFreezeExplicitPairsAndTheirReviews() {
        var f=fixture();var post=queries.posts(f.cycleId(),f.planId()).measurements().getFirst();
        UUID baseProof=measures.context(f.cycleId()).baselines().getFirst().evidenceId();
        workflow.review(f.cycleId(),f.planId(),review(f,baseProof),"approver");workflow.review(f.cycleId(),f.planId(),review(f,post.evidenceId()),"approver");
        var context=workflow.seal(f.cycleId(),f.planId(),seal(f),"approver");var pair=context.set().pairs().getFirst();
        assertNotNull(pair.baselineReviewId());assertNotNull(pair.postReviewId());assertEquals(f.baselineId(),pair.baselineId());assertEquals(post.measurementId(),pair.measurementId());
        assertEquals("EXPLICIT_PAIR_V1",context.set().policyVersion());assertEquals("E08_VERIFICANDO",cycles.getCycle(f.cycleId()).state());
        assertEquals("MEASUREMENT_SET_SEALED",assertThrows(LifecycleException.class,()->workflow.record(f.cycleId(),f.planId(),command(f,context.cycleVersion()),"author")).code());
        assertThrows(LifecycleException.class,()->workflow.review(f.cycleId(),f.planId(),review(f,post.evidenceId()),"approver"));
        assertThrows(LifecycleException.class,()->workflow.seal(f.cycleId(),f.planId(),seal(f),"approver"));
        assertEquals(context,queries.posts(f.cycleId(),f.planId()));
    }

    /** Ausencia de revisión se congela como ausencia; una revisión rechazada tampoco se transforma en suficiente. */
    @Test @DisplayName("Preserva evidencia insuficiente o rechazada al cerrar el conjunto")
    void shouldPreserveMissingOrRejectedReview() {
        var f=fixture();var post=queries.posts(f.cycleId(),f.planId()).measurements().getFirst();var original=review(f,post.evidenceId());
        workflow.review(f.cycleId(),f.planId(),new ReviewMeasure(original.expectedVersion(),f.runId(),post.evidenceId(),ReviewVerdict.REJECTED,
                original.method(),original.procedureReference(),"Fuente no contrastada",original.reason()),"approver");
        var context=workflow.seal(f.cycleId(),f.planId(),seal(f),"approver");
        assertNull(context.set().pairs().getFirst().baselineReviewId());assertNotNull(context.set().pairs().getFirst().postReviewId());
        assertEquals(ReviewVerdict.REJECTED,context.reviews().getFirst().verdict());assertEquals("UNVERIFIED",context.measurements().getFirst().qualityStatus());
    }

    /** Selecciones duplicadas, ventanas erróneas y métodos distintos no generan un conjunto. */
    @Test @DisplayName("Rechaza selección ambigua, ventanas y métodos incompatibles")
    void shouldRejectAmbiguousOrIncomparableSelection() {
        var f=fixture();var valid=seal(f);
        var duplicate=new SealPost(valid.expectedVersion(),f.runId(),valid.baselineFrom(),valid.baselineTo(),valid.postFrom(),valid.postTo(),valid.method(),List.of(valid.pairs().getFirst(),valid.pairs().getFirst()),"duplicado");
        assertEquals("DUPLICATE_MEASUREMENT_PAIR",assertThrows(LifecycleException.class,()->workflow.seal(f.cycleId(),f.planId(),duplicate,"approver")).code());
        var method=new SealPost(valid.expectedVersion(),f.runId(),valid.baselineFrom(),valid.baselineTo(),valid.postFrom(),valid.postTo(),"otro método",valid.pairs(),"incompatible");
        assertEquals("METHOD_MISMATCH",assertThrows(LifecycleException.class,()->workflow.seal(f.cycleId(),f.planId(),method,"approver")).code());
        var window=new SealPost(valid.expectedVersion(),f.runId(),valid.baselineFrom(),valid.baselineTo(),f.baselineAt(),valid.postTo(),valid.method(),valid.pairs(),"ventana");
        assertEquals("MEASUREMENT_WINDOWS_INVALID",assertThrows(LifecycleException.class,()->workflow.seal(f.cycleId(),f.planId(),window,"approver")).code());
        assertNull(queries.posts(f.cycleId(),f.planId()).set());
    }

    /** El conjunto y sus pares se revierten junto con el evento y la versión. */
    @Test @DisplayName("Revierte el conjunto completo si falla su auditoría")
    void shouldRollbackSealWhenAuditFails() {
        var f=fixture();var command=seal(f);
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT test_set_audit CHECK(cycle_id<>CAST('"+f.cycleId()+"' AS UUID) OR event_type<>'MEASUREMENT_SET_SEALED')");
        try {
            assertThrows(RuntimeException.class,()->workflow.seal(f.cycleId(),f.planId(),command,"approver"));
            assertNull(queries.posts(f.cycleId(),f.planId()).set());assertEquals(command.expectedVersion(),cycles.getCycle(f.cycleId()).version());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM measurement_pair WHERE run_id=?",Integer.class,f.runId()));
        } finally {jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT test_set_audit");}
    }
}
