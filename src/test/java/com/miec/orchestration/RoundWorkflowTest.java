package com.miec.orchestration;

import com.miec.decision.DecisionAction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.concurrent.*;
import static com.miec.orchestration.CycleCompletionContracts.*;
import static com.miec.orchestration.RoundContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verifica registro y conclusión con el recorrido real hasta E11. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:round_workflow;DB_CLOSE_DELAY=-1")
@DisplayName("MVP-07.3 — contexto y conclusión operativa")
class RoundWorkflowTest {
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow posts;
    @Autowired CycleCompletionWorkflow completion;
    @Autowired RoundWorkflow rounds;
    @Autowired InterventionPlans plans;
    @Autowired PlanActivityQuery activity;
    @Autowired RoundScope scope;
    @Autowired MeasurementQueries queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.miec.action.ExecutionAttemptService attempts;
    @Autowired com.miec.action.ExecutionEvidenceService evidence;
    /** Evita compartir identidad de fixtures entre pruebas. */
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    /** Recorre medición, evaluación, decisión y aprendizaje sin fabricar estados SQL. */
    private View learned(){
        var ready=CompletionTestSupport.sealed(cycles,measures,intervention,execution,posts);var f=ready.fixture();
        var admitted=completion.evaluate(f.cycleId(),f.planId(),new Evaluate(ready.version(),ready.setId(),"evaluar"),"author");
        var decided=completion.decide(f.cycleId(),new Decide(admitted.completion().cycle().version(),admitted.evaluation().report().result().id(),DecisionAction.CLOSE_CYCLE,true,"selección"),"approver");
        return completion.learn(f.cycleId(),new Learn(decided.cycle().version(),decided.decision().decisionId(),"aprendizaje","laboratorio","sin causalidad","aprender"),"author");
    }
    /** Crea la ronda inicial con la versión observada. */
    private Context registered(){var v=learned();return rounds.register(v.cycle().cycleId(),new RegisterInitial(v.cycle().version(),"contexto inicial"),"author");}
    /** Comando vigente de conclusión. */
    private CompletePlan command(Context c){return new CompletePlan(c.cycle().version(),c.rounds().getFirst().version(),"concluir operativamente");}
    /** Mantiene estado, aprobación, huella y entradas originales tras la conclusión. */
    @Test @DisplayName("Concluye sin reescribir aprobación, evaluación o aprendizaje")
    void preservesHistoricalContext(){
        var c=registered();var r=c.rounds().getFirst();var plan=plans.get(r.planId(),false);var before=completion.view(c.cycle().cycleId());
        assertEquals(List.of(r.planId()),c.activePlanIds());assertEquals("ROUND-CONTEXT-V1",r.snapshot().schemaVersion());
        var result=rounds.conclude(c.cycle().cycleId(),r.roundId(),command(c),"approver");var after=result.rounds().getFirst();
        assertEquals("E11_CONTINUANDO",result.cycle().state());assertEquals(c.cycle().version()+1,result.cycle().version());
        assertEquals(1,after.version());assertEquals("ACTIVE",after.state());assertEquals(r.snapshot(),after.snapshot());
        assertEquals(plan,plans.get(r.planId(),false));assertEquals("PLAN-COMPLETION-V1",after.conclusion().policyVersion());
        assertEquals(before.learning(),completion.view(c.cycle().cycleId()).learning());assertTrue(result.activePlanIds().isEmpty());
        assertEquals("PLAN_CONCLUDED",assertThrows(LifecycleException.class,()->activity.requireOpen(r.planId())).code());
        assertEquals("NEW_ROUND_REQUIRED",assertThrows(LifecycleException.class,()->activity.requireProposalRound(c.cycle().cycleId())).code());
        assertEquals("ROUND_VERSION_CONFLICT",assertThrows(LifecycleException.class,()->rounds.conclude(c.cycle().cycleId(),r.roundId(),new CompletePlan(result.cycle().version(),0L,"repetir"),"approver")).code());
    }
    /** Versiones y separación de funciones rechazan comandos sin efectos. */
    @Test @DisplayName("Rechaza autor, ronda ajena y versiones antiguas")
    void rejectsInvalidCommands(){
        var c=registered();var r=c.rounds().getFirst();UUID cycle=c.cycle().cycleId();
        assertEquals("SELF_COMPLETION_FORBIDDEN",assertThrows(LifecycleException.class,()->rounds.conclude(cycle,r.roundId(),command(c),"author")).code());
        assertEquals("ROUND_NOT_FOUND",assertThrows(LifecycleException.class,()->rounds.conclude(cycle,UUID.randomUUID(),command(c),"approver")).code());
        assertEquals("VERSION_CONFLICT",assertThrows(LifecycleException.class,()->rounds.conclude(cycle,r.roundId(),new CompletePlan(0L,0L,"vieja"),"approver")).code());
        assertNull(rounds.view(cycle).rounds().getFirst().conclusion());assertEquals(c.cycle().version(),cycles.getCycle(cycle).version());
    }
    /** No inventa rondas al consultar y no reabre ciclos finalizados. */
    @Test @DisplayName("Consulta no migra históricos y E14 permanece terminal")
    void rejectsClosedCycle(){
        var v=learned();UUID cycle=v.cycle().cycleId();assertTrue(rounds.view(cycle).rounds().isEmpty());
        var closed=completion.close(cycle,new Close(v.cycle().version(),v.learning().learningId(),"cierre",true,"cerrar"),"approver");
        assertThrows(LifecycleException.class,()->rounds.register(cycle,new RegisterInitial(closed.cycle().version(),"reabrir"),"author"));
        assertTrue(rounds.view(cycle).rounds().isEmpty());
        assertTrue(rounds.view(cycle).activePlanIds().isEmpty());
    }
    /** Dos aprobadores sobre la misma versión producen una sola acta. */
    @Test @DisplayName("Concurrencia confirma una única conclusión y una versión")
    void serializesConclusion() throws Exception {
        var c=registered();var r=c.rounds().getFirst();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> task=()->{gate.await();try{rounds.conclude(c.cycle().cycleId(),r.roundId(),command(c),"approver");return true;}
                catch(LifecycleException e){assertEquals("VERSION_CONFLICT",e.code());return false;}};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(c.cycle().version()+1,cycles.getCycle(c.cycle().cycleId()).version());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM plan_completion WHERE round_id=?",Integer.class,r.roundId()));
    }
    /** Un fallo después de crear el evento revierte también sus cambios y versiones. */
    @Test @DisplayName("Fallo del acta revierte auditoría y versiones conjuntamente")
    void rollsBackConclusion(){
        var c=registered();var r=c.rounds().getFirst();UUID cycle=c.cycle().cycleId();
        int events=jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=?",Integer.class,cycle);
        jdbc.execute("ALTER TABLE plan_completion ADD CONSTRAINT test_round_failure CHECK(plan_id<>CAST('"+r.planId()+"' AS UUID))");
        try {
            assertThrows(RuntimeException.class,()->rounds.conclude(cycle,r.roundId(),command(c),"approver"));
            assertEquals(c.cycle().version(),cycles.getCycle(cycle).version());assertEquals(0,rounds.view(cycle).rounds().getFirst().version());
            assertNull(rounds.view(cycle).rounds().getFirst().conclusion());assertEquals(events,jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=?",Integer.class,cycle));
        } finally {jdbc.execute("ALTER TABLE plan_completion DROP CONSTRAINT test_round_failure");}
    }
    /** La migración protege una sola ronda activa incluso frente a SQL directo. */
    @Test @DisplayName("Base de datos impide duplicar la ronda activa y mezclar contextos")
    void enforcesDatabaseConstraints(){
        var c=registered();var r=c.rounds().getFirst();
        UUID event=jdbc.queryForObject("SELECT event_id FROM cycle_event WHERE cycle_id=? AND event_type='LEARNING_RECORDED'",UUID.class,c.cycle().cycleId());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("""
            INSERT INTO cycle_round SELECT ?,cycle_id,2,round_id,'ACTIVE',cycle_id,0,registered_by,reason,registered_at,NULL,?
            FROM cycle_round WHERE round_id=?
            """,UUID.randomUUID(),event,r.roundId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE round_context SET cycle_id=? WHERE round_id=?",UUID.randomUUID(),r.roundId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE cycle_round SET active_cycle_id=NULL WHERE round_id=?",r.roundId()));
    }
    /** La captura inicial tampoco puede duplicarse bajo solicitudes simultáneas. */
    @Test @DisplayName("Registro concurrente crea una única ronda inicial")
    void serializesRegistration() throws Exception {
        var v=learned();UUID cycle=v.cycle().cycleId();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> task=()->{gate.await();try{rounds.register(cycle,new RegisterInitial(v.cycle().version(),"registrar"),"author");return true;}
                catch(LifecycleException e){assertEquals("VERSION_CONFLICT",e.code());return false;}};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,rounds.view(cycle).rounds().size());assertEquals(v.cycle().version()+1,cycles.getCycle(cycle).version());
    }
    /** Los hechos técnicos tardíos bloquean la conclusión sin borrar la instantánea original. */
    @Test @DisplayName("Contradicción tardía impide concluir y conserva el contexto")
    void rejectsUncertainExecution(){
        var c=registered();var r=c.rounds().getFirst();UUID cycle=c.cycle().cycleId();
        var attempt=jdbc.queryForObject("SELECT plan_id,planned_action_id FROM execution_attempt WHERE cycle_id=?",
            (rs,row)->attempts.findHistory(rs.getObject("plan_id",UUID.class),rs.getObject("planned_action_id",UUID.class)).getFirst(),cycle);
        evidence.report(UUID.randomUUID(),attempt.ownership(),com.miec.action.ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradicción",PostMeasurementTestSupport.now());
        assertEquals("EXECUTION_UNCERTAIN",assertThrows(LifecycleException.class,()->rounds.conclude(cycle,r.roundId(),command(c),"approver")).code());
        assertEquals(r.snapshot(),rounds.view(cycle).rounds().getFirst().snapshot());assertNull(rounds.view(cycle).rounds().getFirst().conclusion());
    }
    /** El historial de criterios no se reconstruye a partir de una definición actual. */
    @Test @DisplayName("Cambios posteriores de criterio no alteran el snapshot registrado")
    void freezesCriteria(){
        var c=registered();var r=c.rounds().getFirst();var pair=r.snapshot().evaluation().inputs().pairs().getFirst();
        jdbc.update("UPDATE indicator SET acceptance_threshold=999 WHERE indicator_id=?",pair.indicatorId());
        assertEquals(r.snapshot(),rounds.view(c.cycle().cycleId()).rounds().getFirst().snapshot());
    }
    /** Cerrar un ciclo con ronda explícita exige el acta y no deja actividad fantasma. */
    @Test @DisplayName("E14 concluye la ronda atómicamente y exige conclusión del plan")
    void closesExplicitRound(){
        var c=registered();var r=c.rounds().getFirst();UUID cycle=c.cycle().cycleId();
        var v=completion.view(cycle);var close=new Close(c.cycle().version(),v.learning().learningId(),"cerrar",true,"cierre administrativo");
        assertEquals("PLAN_COMPLETION_REQUIRED",assertThrows(LifecycleException.class,()->completion.close(cycle,close,"approver")).code());
        assertEquals(c.cycle().version(),cycles.getCycle(cycle).version());
        var concluded=rounds.conclude(cycle,r.roundId(),command(c),"approver");
        completion.close(cycle,new Close(concluded.cycle().version(),v.learning().learningId(),"cerrar",true,"cierre administrativo"),"approver");
        var closed=rounds.view(cycle);assertEquals("E14_FINALIZADO",closed.cycle().state());assertEquals("CONCLUDED",closed.rounds().getFirst().state());
        assertEquals(2,closed.rounds().getFirst().version());assertTrue(closed.activePlanIds().isEmpty());
    }
    /** Las fuentes sin asignación no se incorporan implícitamente a la ronda registrada. */
    @Test @DisplayName("La ronda solo consulta sus muestras explícitamente vinculadas")
    void isolatesUnassignedBaselines(){
        var c=registered();var r=c.rounds().getFirst();UUID cycle=c.cycle().cycleId();
        var before=queries.context(cycle,r.roundId()).baselines();UUID original=before.getFirst().baselineId();
        jdbc.update("""
            INSERT INTO baseline(baseline_id,cycle_id,indicator_id,observed_value,quality_status,measured_at,version_id,created_at,source_reference,recorded_by,unit)
            SELECT ?,cycle_id,indicator_id,999,quality_status,measured_at,version_id,created_at,source_reference,recorded_by,unit FROM baseline WHERE baseline_id=?
            """,UUID.randomUUID(),original);
        assertEquals(before,queries.context(cycle,r.roundId()).baselines());assertEquals(before,queries.context(cycle).baselines());
        assertEquals(r.roundId(),scope.forPlan(r.planId()));
        assertEquals("OBSERVATION_ROUND_MISMATCH",assertThrows(LifecycleException.class,()->scope.requireObservation(cycle,UUID.randomUUID())).code());
    }
    /** Las consultas y claves compuestas rechazan contextos ajenos. */
    @Test @DisplayName("Rechaza referencias de ronda y diagnóstico de otro ciclo")
    void rejectsForeignRoundInputs(){
        var a=registered();var b=registered();var ra=a.rounds().getFirst();var rb=b.rounds().getFirst();
        assertEquals("ROUND_NOT_FOUND",assertThrows(LifecycleException.class,()->queries.context(a.cycle().cycleId(),rb.roundId())).code());
        UUID diagnosis=plans.get(ra.planId(),false).diagnosisId();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE round_diagnosis SET round_id=? WHERE diagnosis_id=?",rb.roundId(),diagnosis));
    }
}
