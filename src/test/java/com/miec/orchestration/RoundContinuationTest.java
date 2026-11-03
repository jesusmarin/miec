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
import static com.miec.orchestration.RoundContracts.*;
import static com.miec.orchestration.CycleCompletionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Continuidad real desde aprendizaje, con independencia humana y transacciones breves. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:round_continuation;DB_CLOSE_DELAY=-1")
@DisplayName("MVP-07.5 — continuidad administrativa entre rondas")
class RoundContinuationTest {
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow posts;
    @Autowired CycleCompletionWorkflow completion;
    @Autowired RoundWorkflow rounds;
    @Autowired RoundContinuationWorkflow continuity;
    @Autowired MeasurementQueries queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.miec.action.ExecutionAttemptService attempts;
    @Autowired com.miec.action.ExecutionEvidenceService evidence;
    /** Limpia el principal utilizado por el adaptador de prueba. */
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    /** Recorre el ciclo completo hasta el acta operativa, sin fabricar estados. */
    Context prepared(DecisionAction action){
        var ready=CompletionTestSupport.sealed(cycles,measures,intervention,execution,posts);var f=ready.fixture();
        var a=completion.evaluate(f.cycleId(),f.planId(),new Evaluate(ready.version(),ready.setId(),"evaluar"),"author");
        var d=completion.decide(f.cycleId(),new Decide(a.completion().cycle().version(),a.evaluation().report().result().id(),action,true,"selección"),"approver");
        var learned=completion.learn(f.cycleId(),new Learn(d.cycle().version(),d.decision().decisionId(),"aprendizaje","laboratorio","Sin causalidad","aprender"),"author");
        var registered=rounds.register(f.cycleId(),new RegisterInitial(learned.cycle().version(),"ronda inicial"),"author");var r=registered.rounds().getFirst();
        return rounds.conclude(f.cycleId(),r.roundId(),new CompletePlan(registered.cycle().version(),r.version(),"concluir"),"approver");
    }
    /** Solicitud de observación que reconoce límites y fija las referencias originales. */
    RequestContinuation collect(Context c){var r=c.rounds().getLast();return new RequestContinuation(c.cycle().version(),r.version(),r.snapshot().decision().decisionId(),
        r.snapshot().learning().learningId(),ContinuationMode.COLLECT_EVIDENCE,true,"observar otra ronda",List.of(),null);}
    /** Aprobación usa exactamente las versiones congeladas al solicitar. */
    ApproveContinuation approval(Continuation r){return new ApproveContinuation(r.cycleVersion(),r.roundVersion(),r.version(),true,"aprobar continuidad limitada");}
    /** Contextos previos permanecen consultables, sin recibos falsos en la ronda nueva. */
    @Test @DisplayName("Continuidad E11 a E02 crea una sola sucesora vacía y conserva historia")
    void continuesToObservation(){
        var c=prepared(DecisionAction.COLLECT_EVIDENCE);var old=c.rounds().getFirst();UUID cycle=c.cycle().cycleId();
        var request=continuity.request(cycle,old.roundId(),collect(c),"author");var next=continuity.approve(cycle,request.requestId(),approval(request),"approver");
        assertEquals("E02_OBSERVANDO",next.cycle().state());assertEquals(2,next.rounds().size());assertEquals("CONCLUDED",next.rounds().getFirst().state());
        assertEquals(old.snapshot(),next.rounds().getFirst().snapshot());assertEquals(old.roundId(),next.rounds().getLast().previousRoundId());
        assertNull(next.rounds().getLast().snapshot());assertNull(completion.view(cycle).admission());assertTrue(queries.context(cycle).baselines().isEmpty());
        assertEquals(old.snapshot().learning(),completion.forRound(cycle,old.roundId()).learning());
        assertEquals("APPROVED",continuity.get(cycle,request.requestId()).status());
    }
    /** ADJUST_PLAN es una resolución humana nueva, sin cambiar la decisión F02 ni elevar EV. */
    @Test @DisplayName("Continuidad E11 a E04 exige y registra línea base y diagnóstico nuevos")
    void continuesToPlanning(){
        var c=prepared(DecisionAction.COLLECT_EVIDENCE);var r=c.rounds().getFirst();var base=collect(c);var pair=r.snapshot().evaluation().inputs().pairs().getFirst();
        var sample=new BaselineInput(pair.indicatorId(),new java.math.BigDecimal("120"),pair.baseline().unit(),"medición nueva",PostMeasurementTestSupport.now());
        var input=new RequestContinuation(base.expectedCycleVersion(),base.expectedRoundVersion(),base.decisionId(),base.learningId(),ContinuationMode.ADJUST_PLAN,true,"ajuste administrativo RS-05",List.of(sample),"diagnóstico nuevo");
        var request=continuity.request(c.cycle().cycleId(),r.roundId(),input,"author");var next=continuity.approve(c.cycle().cycleId(),request.requestId(),approval(request),"approver");
        assertEquals("E04_PLANIFICANDO",next.cycle().state());var fresh=queries.context(c.cycle().cycleId()).baselines().getFirst();
        assertNotEquals(pair.baseline().id(),fresh.baselineId());assertEquals("UNVERIFIED",fresh.qualityStatus());
        assertEquals(r.snapshot(),next.rounds().getFirst().snapshot());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM round_diagnosis WHERE round_id=?",Integer.class,next.rounds().getLast().roundId()));
    }
    /** La intención de cierre y una entrada incompleta no permiten abrir planificación. */
    @Test @DisplayName("Rechaza cierre previo, referencias ajenas e insumos incompletos")
    void rejectsInadmissibleInput(){
        var closed=prepared(DecisionAction.CLOSE_CYCLE);var root=closed.rounds().getFirst();
        assertEquals("CONTINUATION_INADMISSIBLE",assertThrows(LifecycleException.class,()->continuity.request(closed.cycle().cycleId(),root.roundId(),collect(closed),"author")).code());
        var c=prepared(DecisionAction.COLLECT_EVIDENCE);var r=c.rounds().getFirst();var cmd=collect(c);
        var bad=new RequestContinuation(cmd.expectedCycleVersion(),cmd.expectedRoundVersion(),cmd.decisionId(),cmd.learningId(),ContinuationMode.ADJUST_PLAN,true,"ajustar",List.of(),"diagnóstico sin muestras");
        assertEquals("INCOMPLETE_BASELINE",assertThrows(LifecycleException.class,()->continuity.request(c.cycle().cycleId(),r.roundId(),bad,"author")).code());
    }
    /** Dos aprobaciones sobre la misma solicitud no generan ramas o versiones duplicadas. */
    @Test @DisplayName("Aprobación concurrente confirma un único sucesor")
    void serializesApproval() throws Exception {
        var c=prepared(DecisionAction.COLLECT_EVIDENCE);UUID cycle=c.cycle().cycleId();var request=continuity.request(cycle,c.rounds().getFirst().roundId(),collect(c),"author");var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> task=()->{gate.await();try{continuity.approve(cycle,request.requestId(),approval(request),"approver");return true;}
                catch(LifecycleException e){assertEquals("VERSION_CONFLICT",e.code());return false;}};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(2,rounds.view(cycle).rounds().size());assertEquals(request.cycleVersion()+1,cycles.getCycle(cycle).version());
    }
    /** Un fallo después de concluir la ronda revierte también transición, acta de aprobación y sucesora. */
    @Test @DisplayName("Fallo al abrir sucesora revierte toda la continuidad")
    void rollsBackSuccessor(){
        var c=prepared(DecisionAction.COLLECT_EVIDENCE);UUID cycle=c.cycle().cycleId();var request=continuity.request(cycle,c.rounds().getFirst().roundId(),collect(c),"author");
        jdbc.execute("ALTER TABLE cycle_round ADD CONSTRAINT test_successor_failure CHECK(cycle_id<>CAST('"+cycle+"' AS UUID) OR round_number=1)");
        try {
            assertThrows(RuntimeException.class,()->continuity.approve(cycle,request.requestId(),approval(request),"approver"));
            assertEquals("E11_CONTINUANDO",cycles.getCycle(cycle).state());assertEquals(request.cycleVersion(),cycles.getCycle(cycle).version());
            assertEquals(1,rounds.view(cycle).rounds().size());assertEquals("ACTIVE",rounds.view(cycle).rounds().getFirst().state());
            assertEquals("PENDING",continuity.get(cycle,request.requestId()).status());
        } finally {jdbc.execute("ALTER TABLE cycle_round DROP CONSTRAINT test_successor_failure");}
    }
    /** El aprobador no puede ser el autor ni aprobar una captura anterior a cambios del ciclo. */
    @Test @DisplayName("Rechaza autoaprobación y solicitudes obsoletas")
    void rejectsSelfAndStaleRequests(){
        var c=prepared(DecisionAction.COLLECT_EVIDENCE);UUID cycle=c.cycle().cycleId();var first=continuity.request(cycle,c.rounds().getFirst().roundId(),collect(c),"author");
        assertEquals("SELF_CONTINUATION_FORBIDDEN",assertThrows(LifecycleException.class,()->continuity.approve(cycle,first.requestId(),approval(first),"author")).code());
        var current=rounds.view(cycle);var second=continuity.request(cycle,current.rounds().getFirst().roundId(),collect(current),"author");
        var newer=new ApproveContinuation(second.cycleVersion(),first.roundVersion(),first.version(),true,"versión posterior");
        assertEquals("CONTINUATION_STALE",assertThrows(LifecycleException.class,()->continuity.approve(cycle,first.requestId(),newer,"approver")).code());
    }
    /** Una contradicción llegada después de solicitar se revalida al aprobar. */
    @Test @DisplayName("Incertidumbre tardía bloquea la aprobación sin alterar la solicitud")
    void rejectsLateContradiction(){
        var c=prepared(DecisionAction.COLLECT_EVIDENCE);UUID cycle=c.cycle().cycleId();var request=continuity.request(cycle,c.rounds().getFirst().roundId(),collect(c),"author");
        var attempt=jdbc.queryForObject("SELECT plan_id,planned_action_id FROM execution_attempt WHERE cycle_id=?",(rs,row)->attempts.findHistory(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)).getFirst(),cycle);
        evidence.report(UUID.randomUUID(),attempt.ownership(),com.miec.action.ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradicción",PostMeasurementTestSupport.now());
        assertEquals("EXECUTION_UNCERTAIN",assertThrows(LifecycleException.class,()->continuity.approve(cycle,request.requestId(),approval(request),"approver")).code());
        assertEquals("PENDING",continuity.get(cycle,request.requestId()).status());assertEquals(1,rounds.view(cycle).rounds().size());
    }
    /** IDs de la segunda preparación sin transferir fuentes del plan anterior. */
    record NextPlan(InterventionViews.Plan plan,UUID baseline,java.time.Instant measuredAt) { }
    /** Recorre observación y diagnóstico nuevos antes de proponer otro plan. */
    NextPlan nextPlan(Context next){
        UUID cycle=next.cycle().cycleId();var context=queries.context(cycle);var objective=context.objectives().getFirst();var criterion=objective.criteria().getFirst();
        assertTrue(context.baselines().isEmpty());
        var historical=next.rounds().getFirst().snapshot().evaluation().inputs().pairs().getFirst().baseline();
        assertEquals("BASELINE_PRECEDES_PREVIOUS_EXECUTION",assertThrows(LifecycleException.class,()->measures.measure(cycle,new MeasurementContracts.Measure(cycles.getCycle(cycle).version(),criterion.indicatorId(),historical.value(),criterion.unit(),"fecha de ronda anterior",historical.observedAt(),"rechazar observación antigua"),"author")).code());
        assertEquals("INCOMPLETE_BASELINE",assertThrows(LifecycleException.class,()->measures.complete(cycle,new MeasurementContracts.Complete(cycles.getCycle(cycle).version(),"sin datos"),"author")).code());
        var at=PostMeasurementTestSupport.now();var base=measures.measure(cycle,new MeasurementContracts.Measure(cycles.getCycle(cycle).version(),criterion.indicatorId(),new java.math.BigDecimal("125"),criterion.unit(),"nueva observación",at,"base ronda dos"),"author");
        var observed=measures.complete(cycle,new MeasurementContracts.Complete(base.cycle().version(),"cerrar nueva observación"),"author");
        UUID oldEvidence=jdbc.queryForObject("SELECT d.evidence_id FROM cycle_diagnosis d JOIN round_diagnosis r ON r.diagnosis_id=d.diagnosis_id WHERE r.round_id=?",UUID.class,next.rounds().getFirst().roundId());
        assertEquals("OBSERVATION_ROUND_MISMATCH",assertThrows(LifecycleException.class,()->intervention.diagnose(cycle,new InterventionCommands.Diagnose(observed.cycle().version(),oldEvidence,"viejo","rechazar"),"author")).code());
        var diagnosed=intervention.diagnose(cycle,new InterventionCommands.Diagnose(observed.cycle().version(),observed.resourceId(),"diagnóstico segunda ronda","diagnosticar"),"author");
        var action=new InterventionCommands.Action("PROCESS_EXECUTION","nuevo cambio",new com.miec.execution.ExecutableActions.Spec("test-recipient","1","test-destination","application/json","{}"));
        var plan=intervention.propose(cycle,new InterventionCommands.ProposePlan(diagnosed.cycle().version(),objective.objectiveId(),"nuevo resultado","nuevo rollback",List.of(action)),"author");
        return new NextPlan(plan,base.resourceId(),at);
    }
    /** Abre observación por el comando de continuidad real. */
    Context observationRound(){var c=prepared(DecisionAction.COLLECT_EVIDENCE);var r=c.rounds().getFirst();var request=continuity.request(c.cycle().cycleId(),r.roundId(),collect(c),"author");return continuity.approve(c.cycle().cycleId(),request.requestId(),approval(request),"approver");}
    /** La aprobación histórica no concede ejecución a acciones o planes recién creados. */
    @Test @DisplayName("Nueva ronda exige nuevas muestras, diagnóstico, plan, acciones y permisos")
    void requiresNewPlanAndPermissions(){
        var next=observationRound();UUID cycle=next.cycle().cycleId();var fresh=nextPlan(next);var plan=fresh.plan();var old=next.rounds().getFirst();
        assertNotEquals(old.planId(),plan.planId());assertNotEquals(plansFor(old.planId()).actions().getFirst().actionId(),plan.actions().getFirst().actionId());
        assertThrows(LifecycleException.class,()->intervention.requestAuthorization(old.planId(),new InterventionCommands.RequestAuthorization(plansFor(old.planId()).version(),plansFor(old.planId()).actions().getFirst().actionId(),600L),"author"));
        var permit=intervention.requestAuthorization(plan.planId(),new InterventionCommands.RequestAuthorization(plan.version(),plan.actions().getFirst().actionId(),600L),"author");
        PostMeasurementTestSupport.authenticate("AUTHOR");
        assertThrows(LifecycleException.class,()->execution.start(cycle,plan.planId(),new PlanExecutionContracts.Start(cycles.getCycle(cycle).version(),intervention.getPlan(plan.planId()).version(),"sin aprobación nueva")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM execution_attempt WHERE plan_id=?",Integer.class,plan.planId()));
        intervention.decide(permit.authorizationId(),new InterventionCommands.Decide(permit.version(),"aprobar nuevo plan"),true,"approver");
        var approved=intervention.getPlan(plan.planId());var run=execution.start(cycle,plan.planId(),new PlanExecutionContracts.Start(cycles.getCycle(cycle).version(),approved.version(),"ejecutar segunda ronda"));
        assertEquals("APPLIED",run.state());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM execution_attempt WHERE plan_id=? AND authorization_id=?",Integer.class,plan.planId(),permit.authorizationId()));
        assertEquals("APPROVED",plansFor(old.planId()).status());
    }
    /** Consulta el contenido aprobado sin exigir que sea el plan activo. */
    private InterventionViews.Plan plansFor(UUID plan){return intervention.getPlan(plan);}
    /** Ejecuta el nuevo plan y fija un conjunto con muestras de su propia ronda. */
    CompletionTestSupport.Ready secondSet(Context next){
        UUID cycle=next.cycle().cycleId();var fresh=nextPlan(next);var plan=fresh.plan();
        var permission=intervention.requestAuthorization(plan.planId(),new InterventionCommands.RequestAuthorization(plan.version(),plan.actions().getFirst().actionId(),600L),"author");
        intervention.decide(permission.authorizationId(),new InterventionCommands.Decide(0L,"aprobar ronda nueva"),true,"approver");
        PostMeasurementTestSupport.authenticate("AUTHOR");var approved=intervention.getPlan(plan.planId());
        var run=execution.start(cycle,plan.planId(),new PlanExecutionContracts.Start(cycles.getCycle(cycle).version(),approved.version(),"segunda ejecución"));
        PostMeasurementTestSupport.authenticate("APPROVER");execution.finish(plan.planId(),new PlanExecutionContracts.Finish(run.version(),cycles.getCycle(cycle).version(),"confirmar segunda ejecución"));
        var indicator=queries.context(cycle).baselines().getFirst().indicatorId();var fixture=new PostMeasurementTestSupport.Fixture(cycle,plan.planId(),run.runId(),indicator,fresh.baseline(),fresh.measuredAt());
        var context=posts.record(cycle,plan.planId(),PostMeasurementTestSupport.command(fixture,cycles.getCycle(cycle).version()),"author");var post=context.measurements().getFirst();
        var old=next.rounds().getFirst().snapshot().evaluation().inputs().pairs().getFirst().baseline().id();
        var wrong=new MeasurementContracts.SealPost(context.cycleVersion(),run.runId(),fresh.measuredAt().minusSeconds(1),fresh.measuredAt(),post.measuredAt(),PostMeasurementTestSupport.now(),post.method(),
            List.of(new MeasurementContracts.PairSelection(indicator,old,post.measurementId())),"no mezclar muestras");
        assertEquals("INVALID_BASELINE_SELECTION",assertThrows(LifecycleException.class,()->posts.seal(cycle,plan.planId(),wrong,"approver")).code());
        var sealed=posts.seal(cycle,plan.planId(),new MeasurementContracts.SealPost(context.cycleVersion(),run.runId(),fresh.measuredAt().minusSeconds(1),fresh.measuredAt(),post.measuredAt(),PostMeasurementTestSupport.now(),post.method(),
            List.of(new MeasurementContracts.PairSelection(indicator,fresh.baseline(),post.measurementId())),"cerrar conjunto propio"),"approver");
        return new CompletionTestSupport.Ready(fixture,sealed.set().setId(),sealed.cycleVersion());
    }
    /** Dos conclusiones del mismo ciclo se mantienen separadas por ronda y conservan sus políticas originales. */
    @Test @DisplayName("Dos rondas completas conservan evaluaciones, decisiones, aprendizaje y cierre independientes")
    void completesTwoRounds(){
        var next=observationRound();UUID cycle=next.cycle().cycleId();var old=next.rounds().getFirst();var ready=secondSet(next);var fixture=ready.fixture();
        assertEquals("PLAN_ROUND_MISMATCH",assertThrows(LifecycleException.class,()->completion.evaluate(cycle,old.planId(),new Evaluate(ready.version(),ready.setId(),"plan anterior"),"author")).code());
        var evaluated=completion.evaluate(cycle,fixture.planId(),new Evaluate(ready.version(),ready.setId(),"evaluar segunda ronda"),"author");var view=evaluated.completion();
        assertNotEquals(old.snapshot().evaluation().report().result().id(),view.admission().evaluationId());
        assertEquals("EVALUATION_NOT_ADMITTED",assertThrows(LifecycleException.class,()->completion.decide(cycle,new Decide(view.cycle().version(),old.snapshot().evaluation().report().result().id(),DecisionAction.CLOSE_CYCLE,true,"evaluación vieja"),"approver")).code());
        var decided=completion.decide(cycle,new Decide(view.cycle().version(),view.admission().evaluationId(),DecisionAction.CLOSE_CYCLE,true,"cerrar segunda ronda"),"approver");
        var learned=completion.learn(cycle,new Learn(decided.cycle().version(),decided.decision().decisionId(),"aprendizaje ronda dos","laboratorio","Sin causalidad","aprender"),"author");
        var registered=rounds.view(cycle);var current=registered.rounds().getLast();assertNotNull(current.snapshot());
        var concluded=rounds.conclude(cycle,current.roundId(),new CompletePlan(learned.cycle().version(),current.version(),"concluir segundo plan"),"approver");
        var closed=completion.close(cycle,new Close(concluded.cycle().version(),learned.learning().learningId(),"cierre de dos rondas",true,"cerrar"),"approver");
        assertEquals("E14_FINALIZADO",closed.cycle().state());assertEquals(old.snapshot(),rounds.view(cycle).rounds().getFirst().snapshot());
        assertEquals(old.snapshot().decision(),completion.forRound(cycle,old.roundId()).decision());assertNull(completion.forRound(cycle,old.roundId()).closure());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_admission WHERE cycle_id=?",Integer.class,cycle));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM human_decision WHERE cycle_id=?",Integer.class,cycle));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM learning WHERE cycle_id=? AND decision_id IS NOT NULL",Integer.class,cycle));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cycle_round WHERE cycle_id=? AND round_state='ACTIVE'",Integer.class,cycle));
    }
    /** Actualiza una base V018 con un contexto real, conservando su snapshot y asignando solo sus recibos acreditados. */
    @Test @DisplayName("V018 a V021 conserva una ronda registrada y sus recibos reales")
    void upgradesRegisteredContext(){
        var original=prepared(DecisionAction.COLLECT_EVIDENCE);var round=original.rounds().getFirst();UUID cycle=original.cycle().cycleId();
        var input=round.snapshot().evaluation().inputs();var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:h2:mem:registered_upgrade_"+UUID.randomUUID(),"sa","",true);
        try {
            org.flywaydb.core.Flyway.configure().dataSource(source).target("018").load().migrate();var target=new JdbcTemplate(source);
            for(String table:List.of("system_objective","cycle","objective","cycle_objective","indicator","evidence","baseline","cycle_diagnosis","intervention_plan",
                    "plan_execution","post_measurement","measurement_review","measurement_set","measurement_pair","evaluation","decision_record","evaluation_admission","human_decision","learning","cycle_event","cycle_round","round_context","plan_completion")){
                // Events precede the operational receipts that reference them.
                if(table.equals("evaluation_admission"))copyOldTable(target,"cycle_event","cycle_id",cycle);
                if(table.equals("cycle_event"))continue;
                String field=table.equals("system_objective")?"system_id":table.equals("objective")?"objective_id":table.equals("indicator")?"objective_id":"cycle_id";
                UUID id=table.equals("system_objective")?original.cycle().systemId():table.equals("objective")||table.equals("indicator")?input.objectiveId():cycle;
                copyOldTable(target,table,field,id);
            }
            var oldAdmission=target.queryForMap("SELECT * FROM evaluation_admission WHERE cycle_id=?",cycle);
            var oldDecision=target.queryForMap("SELECT * FROM human_decision WHERE cycle_id=?",cycle);
            String snapshot=target.queryForObject("SELECT context_snapshot FROM round_context WHERE round_id=?",String.class,round.roundId());
            assertEquals(3,org.flywaydb.core.Flyway.configure().dataSource(source).target("021").load().migrate().migrationsExecuted);
            var admission=target.queryForMap("SELECT * FROM evaluation_admission WHERE cycle_id=?",cycle);var decision=target.queryForMap("SELECT * FROM human_decision WHERE cycle_id=?",cycle);
            oldAdmission.forEach((key,value)->assertEquals(value,admission.get(key)));oldDecision.forEach((key,value)->assertEquals(value,decision.get(key)));
            assertEquals(round.roundId(),admission.get("ROUND_ID"));assertEquals(round.roundId(),decision.get("ROUND_ID"));assertNull(admission.get("LEGACY_CYCLE_ID"));
            assertEquals(snapshot,target.queryForObject("SELECT context_snapshot FROM round_context WHERE round_id=?",String.class,round.roundId()));
            assertEquals(round.planId(),target.queryForObject("SELECT plan_id FROM round_plan_binding WHERE round_id=?",UUID.class,round.roundId()));
            assertEquals(round.snapshot().evaluation().report().result().id(),target.queryForObject("SELECT evaluation_id FROM round_evaluation_binding WHERE round_id=?",UUID.class,round.roundId()));
        } finally {source.destroy();}
    }
    /** Metadatos del esquema antiguo, para no copiar columnas que todavía no existían. */
    private record OldColumn(String name,boolean json) { }
    /** Copia únicamente filas del fixture y columnas de V018; mantiene JSON como documento, no como cadena. */
    private void copyOldTable(JdbcTemplate target,String table,String field,UUID id){
        var columns=target.query("SELECT * FROM "+table+" WHERE 1=0",(org.springframework.jdbc.core.ResultSetExtractor<List<OldColumn>>)rs->{
            var result=new ArrayList<OldColumn>();var meta=rs.getMetaData();
            for(int index=1;index<=meta.getColumnCount();index++)result.add(new OldColumn(meta.getColumnName(index),"JSON".equalsIgnoreCase(meta.getColumnTypeName(index))));
            return result;
        });
        String names=String.join(",",columns.stream().map(OldColumn::name).toList());String values=String.join(",",columns.stream().map(c->c.json()?"? FORMAT JSON":"?").toList());
        for(var row:jdbc.queryForList("SELECT "+names+" FROM "+table+" WHERE "+field+"=?",id)){
            var parameters=columns.stream().map(c->{Object value=row.get(c.name());return c.json()&&value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):value;}).toArray();
            target.update("INSERT INTO "+table+"("+names+") VALUES ("+values+")",parameters);
        }
    }
}
