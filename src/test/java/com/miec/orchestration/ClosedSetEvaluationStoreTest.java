package com.miec.orchestration;

import com.miec.action.*;
import com.miec.decision.*;
import com.miec.evidence.EvidenceSufficiency;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.PostMeasurementTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Persistencia interna, rollback, contexto IR-009 e identidad estable de la conclusión. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:closed_eval_store;DB_CLOSE_DELAY=-1")
@DisplayName("MVP-06.3 — snapshot y conclusión inmutables")
class ClosedSetEvaluationStoreTest {
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow posts;
    @Autowired ClosedSetEvaluationStore store;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionAttemptService attempts;
    @Autowired ExecutionEvidenceService evidence;
    /** Libera únicamente la identidad instalada por el fixture. */
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    /** Selección explícita generada mediante el flujo real, sin alterar estados por SQL. */
    private record Ready(Fixture fixture,UUID setId,long version) { }
    /** Crea E08 medible y conjunto cerrado con evidencia humana no revisada. */
    private Ready ready() {
        var f=PostMeasurementTestSupport.ready(cycles,measures,intervention,execution);
        var context=posts.record(f.cycleId(),f.planId(),command(f,cycles.getCycle(f.cycleId()).version()),"author");var post=context.measurements().getFirst();
        var sealed=posts.seal(f.cycleId(),f.planId(),new SealPost(context.cycleVersion(),f.runId(),f.baselineAt().minusSeconds(1),f.baselineAt().plusSeconds(1),
                post.measuredAt(),now(),post.method(),List.of(new PairSelection(f.indicatorId(),f.baselineId(),post.measurementId())),"conjunto"),"approver");
        return new Ready(f,sealed.set().setId(),sealed.cycleVersion());
    }
    /** Guardado interno dentro de una transacción que el futuro caso de uso puede ampliar con auditoría/F01. */
    private EvaluationContracts.Saved persist(Ready ready) {
        return new TransactionTemplate(transactions).execute(tx->store.persist(ready.fixture().planId(),ready.setId(),ready.version(),"approver"));
    }

    /** Guarda contexto completo y una conclusión conservadora sin adelantar el paso 06.4. */
    @Test @DisplayName("Persiste snapshot y evidencia, conserva E08 y devuelve la misma conclusión al repetir")
    void shouldPersistRecoverAndDeduplicateWithoutTransition() {
        var f=ready();var before=cycles.getCycle(f.fixture().cycleId());var saved=persist(f);var result=saved.report().result();
        assertEquals(ResultClassification.INCONCLUSIVE,result.classification());assertEquals(EvidenceSufficiency.INSUFFICIENT,result.evidenceAssessment().sufficiency());
        assertEquals(saved,store.get(result.id()));assertEquals(saved,persist(f));assertEquals(List.of(saved),store.forSet(f.setId()));
        assertEquals(before,cycles.getCycle(f.fixture().cycleId()));assertEquals("E08_VERIFICANDO",before.state());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation WHERE measurement_set_id=?",Integer.class,f.setId()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_evidence WHERE evaluation_id=?",Integer.class,result.id()));
        assertEquals(saved.report().inputFingerprint(),ClosedSetEvaluation.fingerprint(saved.inputs()));
        jdbc.update("UPDATE post_measurement SET observed_value=observed_value+10 WHERE run_id=?",f.fixture().runId());
        assertEquals(saved,store.get(result.id())); // Lectura histórica independiente de datos actuales.
    }

    /** El futuro orquestador puede revertir toda la escritura al fallar evento/transición. */
    @Test @DisplayName("Exige transacción exterior y permite rollback completo del llamador")
    void shouldRequireTransactionAndRollbackWithCaller() {
        var f=ready();assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->store.persist(f.fixture().planId(),f.setId(),f.version(),"approver"));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(tx->{store.persist(f.fixture().planId(),f.setId(),f.version(),"approver");throw new IllegalStateException("fallo posterior de test");}));
        assertTrue(store.forSet(f.setId()).isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_evidence ee JOIN evaluation e ON e.evaluation_id=ee.evaluation_id WHERE e.measurement_set_id=?",Integer.class,f.setId()));
    }

    /** Fallar el INSERT de una evidencia referenciada revierte también la fila evaluation. */
    @Test @DisplayName("Revierte evaluación si falla persistir su relación de evidencia")
    void shouldRollbackOnEvidenceInsertFailure() {
        var f=ready();UUID proof=measures.context(f.fixture().cycleId()).baselines().getFirst().evidenceId();
        jdbc.execute("ALTER TABLE evaluation_evidence ADD CONSTRAINT test_eval_proof CHECK(evidence_id<>CAST('"+proof+"' AS UUID))");
        try {assertThrows(RuntimeException.class,()->persist(f));assertTrue(store.forSet(f.setId()).isEmpty());}
        finally {jdbc.execute("ALTER TABLE evaluation_evidence DROP CONSTRAINT test_eval_proof");}
    }

    /** La reconstrucción usa solo el set solicitado, con su versión y hechos técnicos aún válidos. */
    @Test @DisplayName("Rechaza versión, conjunto ajeno y contradicción técnica tardía")
    void shouldRejectStaleForeignOrConflictingContext() {
        var f=ready();var other=ready();
        assertEquals("VERSION_CONFLICT",assertThrows(LifecycleException.class,()->new TransactionTemplate(transactions).execute(tx->store.persist(f.fixture().planId(),f.setId(),0L,"approver"))).code());
        assertEquals("EVALUATION_SET_MISMATCH",assertThrows(LifecycleException.class,()->new TransactionTemplate(transactions).execute(tx->store.persist(f.fixture().planId(),other.setId(),f.version(),"approver"))).code());
        var action=execution.get(f.fixture().planId()).steps().getFirst().actionId();var attempt=attempts.findHistory(f.fixture().planId(),action).getFirst();
        evidence.report(UUID.randomUUID(),attempt.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradicción",now());
        assertEquals("EXECUTION_NOT_CONFIRMED",assertThrows(LifecycleException.class,()->persist(f)).code());assertTrue(store.forSet(f.setId()).isEmpty());
    }

    /** Dos llamadores serializados bajo el mismo plan recuperan la misma fila, nunca dos conclusiones. */
    @Test @DisplayName("Concurrencia conserva una conclusión única por conjunto y políticas")
    void shouldDeduplicateConcurrentWrites() throws Exception {
        var f=ready();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<EvaluationContracts.Saved> write=()->{gate.await();return persist(f);};
            var first=pool.submit(write);var second=pool.submit(write);gate.countDown();assertEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,store.forSet(f.setId()).size());
    }
}
