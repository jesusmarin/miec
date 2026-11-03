package com.miec.orchestration;

import com.miec.decision.ResultClassification;
import com.miec.decision.DecisionAction;
import com.miec.action.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.concurrent.*;
import static com.miec.orchestration.CycleCompletionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recorrido real de admisión, decisión, aprendizaje y cierre con rollback e invariantes. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:cycle_completion;DB_CLOSE_DELAY=-1")
@DisplayName("MVP-06.4–06.6 — cierre transaccional del ciclo")
class CycleCompletionWorkflowTest {
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow posts;
    @Autowired CycleCompletionWorkflow workflow;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionAttemptService attempts;
    @Autowired ExecutionEvidenceService evidence;
    /** Libera la identidad simulada por el fixture. */
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    /** Cada prueba utiliza un conjunto y ciclo independientes. */
    private CompletionTestSupport.Ready ready(){return CompletionTestSupport.sealed(cycles,measures,intervention,execution,posts);}
    /** Comando con la revisión leída del ciclo E08. */
    private Evaluate command(CompletionTestSupport.Ready r){return new Evaluate(r.version(),r.setId(),"evaluar conjunto");}
    /** Contexto admitido por la ruta transaccional real. */
    private EvaluationCreated admitted(){var r=ready();return workflow.evaluate(r.fixture().cycleId(),r.fixture().planId(),command(r),"author");}
    /** Selección de cierre administrativo que conserva la clasificación inconclusa. */
    private View chosen(DecisionAction action){var c=admitted();return workflow.decide(c.completion().cycle().cycleId(),new Decide(c.completion().cycle().version(),c.evaluation().report().result().id(),action,true,"intención humana"),"approver");}
    /** Comando de aprendizaje con sus límites explícitos. */
    private Learn learningCommand(View view){return new Learn(view.cycle().version(),view.decision().decisionId(),"La muestra no acredita mejora","laboratorio local","Sin causalidad ni evidencia instrumental","registrar aprendizaje");}
    /** E11 real, todavía abierto, para comprobar cierre y guardas. */
    private View learned(DecisionAction action){var v=chosen(action);return workflow.learn(v.cycle().cycleId(),learningCommand(v),"author");}
    /** Cierre que reconoce limitaciones sin cambiar RS-05. */
    private Close closeCommand(View view){return new Close(view.cycle().version(),view.learning().learningId(),"Finalización sin conclusión favorable",true,"cierre administrativo");}
    /** No convierte insuficiencia en éxito y confirma evento, transición y admisión conjuntamente. */
    @Test @DisplayName("Admite evaluación inconclusa y avanza a E09 atómicamente")
    void shouldAdmitEvaluationWithAudit() {
        var r=ready();var f=r.fixture();var created=workflow.evaluate(f.cycleId(),f.planId(),command(r),"author");
        assertEquals(ResultClassification.INCONCLUSIVE,created.evaluation().report().result().classification());
        assertEquals("E09_DECIDIENDO",created.completion().cycle().state());assertEquals(r.version()+1,created.completion().cycle().version());
        assertEquals(created.evaluation().report().result().id(),created.completion().admission().evaluationId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM state_transition WHERE cycle_id=? AND to_state='E09_DECIDIENDO'",Integer.class,f.cycleId()));
        assertThrows(LifecycleException.class,()->workflow.evaluate(f.cycleId(),f.planId(),command(r),"author"));
    }
    /** Un fallo real del evento revierte evaluación, evidencia y recibo además del estado/versión. */
    @Test @DisplayName("Fallo de auditoría revierte toda la creación operativa")
    void shouldRollbackEvaluationWhenAuditFails() {
        var r=ready();var f=r.fixture();
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT test_evaluation_audit CHECK(cycle_id<>CAST('"+f.cycleId()+"' AS UUID) OR event_type<>'EVALUATION_RECORDED')");
        try {
            assertThrows(RuntimeException.class,()->workflow.evaluate(f.cycleId(),f.planId(),command(r),"author"));
            assertEquals("E08_VERIFICANDO",cycles.getCycle(f.cycleId()).state());assertEquals(r.version(),cycles.getCycle(f.cycleId()).version());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation WHERE measurement_set_id=?",Integer.class,r.setId()));
            assertNull(workflow.view(f.cycleId()).admission());
        } finally {jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT test_evaluation_audit");}
    }
    /** Dos solicitudes no crean dos admisiones ni avanzan dos veces la versión del ciclo. */
    @Test @DisplayName("Concurrencia admite una sola evaluación operativa")
    void shouldSerializeEvaluationAdmission() throws Exception {
        var r=ready();var f=r.fixture();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{gate.await();try{workflow.evaluate(f.cycleId(),f.planId(),command(r),"author");return true;}
                catch(LifecycleException conflict){assertEquals("VERSION_CONFLICT",conflict.code());return false;}};
            var first=pool.submit(call);var second=pool.submit(call);gate.countDown();assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(r.version()+1,cycles.getCycle(f.cycleId()).version());
    }
    /** La decisión humana se distingue de la recomendación y preserva RS-05 con reconocimiento de límites. */
    @Test @DisplayName("Decisión humana admisible y separada del autor, sin elevar la evaluación")
    void shouldRecordExplicitHumanChoice() {
        var c=admitted();var cycle=c.completion().cycle();var id=c.evaluation().report().result().id();
        var command=new Decide(cycle.version(),id,DecisionAction.CLOSE_CYCLE,true,"cerrar sin declarar éxito");
        assertEquals(DecisionAction.COLLECT_EVIDENCE,workflow.preview(id).recommendation().action());
        assertEquals("SELF_DECISION_FORBIDDEN",assertThrows(LifecycleException.class,()->workflow.decide(cycle.cycleId(),command,"author")).code());
        assertThrows(LifecycleException.class,()->workflow.decide(cycle.cycleId(),new Decide(cycle.version(),id,DecisionAction.CLOSE_CYCLE,false,"no reconocer"),"approver"));
        assertEquals("DECISION_ACTION_INADMISSIBLE",assertThrows(LifecycleException.class,()->workflow.decide(cycle.cycleId(),new Decide(cycle.version(),id,DecisionAction.REVERT_CHANGE,true,"sin permiso"),"approver")).code());
        var result=workflow.decide(cycle.cycleId(),command,"approver");assertEquals("E10_APRENDIENDO",result.cycle().state());
        assertEquals(DecisionAction.CLOSE_CYCLE,result.decision().selectedAction());assertEquals(DecisionAction.COLLECT_EVIDENCE,result.decision().recommendation().recommendation().action());
        assertEquals(ResultClassification.INCONCLUSIVE,result.decision().recommendation().evaluation().classification());
        assertThrows(LifecycleException.class,()->workflow.decide(cycle.cycleId(),command,"approver"));
    }
    /** Fallar auditoría después de guardar la propuesta revierte propuesta, selección y transición. */
    @Test @DisplayName("Rollback de recomendación y decisión si falla su evento")
    void shouldRollbackHumanDecisionAuditFailure() {
        var c=admitted();var cycle=c.completion().cycle();
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT test_decision_audit CHECK(cycle_id<>CAST('"+cycle.cycleId()+"' AS UUID) OR event_type<>'DECISION_RECORDED')");
        try {
            assertThrows(RuntimeException.class,()->workflow.decide(cycle.cycleId(),new Decide(cycle.version(),c.evaluation().report().result().id(),DecisionAction.CLOSE_CYCLE,true,"cerrar"),"approver"));
            assertEquals("E09_DECIDIENDO",cycles.getCycle(cycle.cycleId()).state());assertNull(workflow.view(cycle.cycleId()).decision());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM decision_record WHERE cycle_id=?",Integer.class,cycle.cycleId()));
        } finally {jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT test_decision_audit");}
    }
    /** Aprender no eleva EV/confianza y no ejecuta una recomendación estratégica. */
    @Test @DisplayName("Aprendizaje trazable conserva confianza y avanza solo a E11")
    void shouldRecordLearningWithoutUpgradingEvidence() {
        var chosen=chosen(DecisionAction.CLOSE_CYCLE);var result=workflow.learn(chosen.cycle().cycleId(),learningCommand(chosen),"author");
        assertEquals("E11_CONTINUANDO",result.cycle().state());assertEquals(chosen.decision().decisionId(),result.learning().decisionId());
        assertEquals(chosen.decision().recommendation().evaluation().confidenceLevel(),result.learning().confidence());assertEquals("author",result.learning().recordedBy());
        assertEquals(ResultClassification.INCONCLUSIVE,result.decision().recommendation().evaluation().classification());
        assertThrows(LifecycleException.class,()->workflow.learn(chosen.cycle().cycleId(),learningCommand(chosen),"author"));
    }
    /** Fallar el evento no deja un aprendizaje huérfano ni adelanta la versión del ciclo. */
    @Test @DisplayName("Rollback de aprendizaje y versión si falla el evento")
    void shouldRollbackLearningAuditFailure() {
        var chosen=chosen(DecisionAction.CLOSE_CYCLE);var id=chosen.cycle().cycleId();
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT test_learning_audit CHECK(cycle_id<>CAST('"+id+"' AS UUID) OR event_type<>'LEARNING_RECORDED')");
        try {
            assertThrows(RuntimeException.class,()->workflow.learn(id,learningCommand(chosen),"author"));
            assertEquals(chosen.cycle().version(),cycles.getCycle(id).version());assertNull(workflow.view(id).learning());
        } finally {jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT test_learning_audit");}
    }
    /** Persiste closed_at junto al acta y conserva la conclusión original. */
    @Test @DisplayName("Cierre E14 conserva RS-05, fecha y auditoría coherentes")
    void shouldCloseWithConsistentTimestampWithoutDeclaringSuccess() {
        var learned=learned(DecisionAction.CLOSE_CYCLE);var id=learned.cycle().cycleId();var command=closeCommand(learned);
        var result=workflow.close(id,command,"approver");assertEquals("E14_FINALIZADO",result.cycle().state());assertFalse(result.requiresReview());
        assertEquals(ResultClassification.INCONCLUSIVE,result.decision().recommendation().evaluation().classification());
        var time=jdbc.queryForObject("SELECT closed_at FROM cycle WHERE cycle_id=?",(rs,row)->rs.getTimestamp(1).toInstant(),id);assertEquals(result.closure().closedAt(),time);
        assertEquals(time,jdbc.queryForObject("SELECT e.occurred_at FROM cycle_event e JOIN cycle_closure c ON c.event_id=e.event_id WHERE c.cycle_id=?",(rs,row)->rs.getTimestamp(1).toInstant(),id));
        assertEquals(learned.cycle().version()+1,result.cycle().version());assertThrows(LifecycleException.class,()->workflow.close(id,command,"approver"));
    }
    /** El cierre no es una salida genérica para una intención distinta o un estado sin aprendizaje. */
    @Test @DisplayName("No cierra sin aprendizaje ni con otra intención humana")
    void shouldRequireLearningAndExplicitClosureChoice() {
        var chosen=chosen(DecisionAction.CLOSE_CYCLE);
        assertEquals("LEARNING_REQUIRED",assertThrows(LifecycleException.class,()->workflow.close(chosen.cycle().cycleId(),new Close(chosen.cycle().version(),UUID.randomUUID(),"no",true,"no"),"approver")).code());
        var learned=learned(DecisionAction.COLLECT_EVIDENCE);
        assertEquals("CLOSURE_NOT_SELECTED",assertThrows(LifecycleException.class,()->workflow.close(learned.cycle().cycleId(),closeCommand(learned),"approver")).code());
        assertEquals("E11_CONTINUANDO",cycles.getCycle(learned.cycle().cycleId()).state());
    }
    /** Una incertidumbre de otro plan histórico también bloquea el cierre del ciclo. */
    @Test @DisplayName("Bloquea cierre por PENDING en otro plan del mismo ciclo")
    void shouldInspectAllHistoricalAttemptsBeforeClosing() {
        var learned=learned(DecisionAction.CLOSE_CYCLE);var id=learned.cycle().cycleId();var plan=UUID.randomUUID();var action=UUID.randomUUID();var permission=UUID.randomUUID();
        jdbc.update("INSERT INTO intervention_plan(plan_id,cycle_id,status) VALUES (?,?,'REJECTED')",plan,id);
        jdbc.update("INSERT INTO authorizations(authorization_id,plan_id,action_id,requested_by,scope,status,issued_at,approved_by) VALUES (?,?,?,'legacy','EXECUTE_ACTION','APPROVED',CURRENT_TIMESTAMP,'approver')",permission,plan,action);
        jdbc.update("INSERT INTO execution_attempt(execution_id,planned_action_id,plan_id,cycle_id,authorization_id,attempt_number,execution_status,effect_outcome,actor_id) VALUES (?,?,?,?,?,1,'PENDING','EFFECT_UNKNOWN','legacy')",UUID.randomUUID(),action,plan,id,permission);
        assertEquals("EXECUTION_UNCERTAIN",assertThrows(LifecycleException.class,()->workflow.close(id,closeCommand(learned),"approver")).code());assertTrue(workflow.view(id).requiresReview());
    }
    /** Fallar el acta después de actualizar closed_at revierte todo el cierre. */
    @Test @DisplayName("Fallo del acta revierte E14 y closed_at")
    void shouldRollbackClosureReceiptFailure() {
        var learned=learned(DecisionAction.CLOSE_CYCLE);var id=learned.cycle().cycleId();
        jdbc.execute("ALTER TABLE cycle_closure ADD CONSTRAINT test_closure_receipt CHECK(cycle_id<>CAST('"+id+"' AS UUID))");
        try {
            assertThrows(RuntimeException.class,()->workflow.close(id,closeCommand(learned),"approver"));assertEquals("E11_CONTINUANDO",cycles.getCycle(id).state());
            assertNull(jdbc.queryForObject("SELECT closed_at FROM cycle WHERE cycle_id=?",(rs,row)->rs.getTimestamp(1),id));assertNull(workflow.view(id).closure());
        } finally {jdbc.execute("ALTER TABLE cycle_closure DROP CONSTRAINT test_closure_receipt");}
    }
    /** Dos aprobadores concurrentes no crean dos cierres; evidencia tardía no borra el cierre histórico. */
    @Test @DisplayName("Cierre único concurrente y contradicción tardía visible")
    void shouldCloseOnceAndExposeLateContradiction() throws Exception {
        var learned=learned(DecisionAction.CLOSE_CYCLE);var id=learned.cycle().cycleId();var command=closeCommand(learned);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{gate.await();try{workflow.close(id,command,"approver");return true;}catch(LifecycleException conflict){assertEquals("VERSION_CONFLICT",conflict.code());return false;}};
            var first=pool.submit(call);var second=pool.submit(call);gate.countDown();assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        var attempt=jdbc.queryForObject("SELECT execution_id,plan_id,planned_action_id FROM execution_attempt WHERE cycle_id=?",(rs,row)->attempts.findHistory(rs.getObject("plan_id",UUID.class),rs.getObject("planned_action_id",UUID.class)).getFirst(),id);
        evidence.report(UUID.randomUUID(),attempt.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradicción tardía",PostMeasurementTestSupport.now());
        var current=workflow.view(id);assertEquals("E14_FINALIZADO",current.cycle().state());assertNotNull(current.closure());assertTrue(current.requiresReview());
    }
}
