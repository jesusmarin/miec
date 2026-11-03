package com.miec.orchestration;

import com.miec.action.*;
import com.miec.execution.ExecutableActionCatalog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.miec.orchestration.PlanExecutionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recorrido real hasta IR-009, fallos entre commits, roles, permisos vencidos y resultados contradictorios. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:plan_execution_application;DB_CLOSE_DELAY=-1")
class PlanExecutionApplicationTest {
    @Autowired LifecycleService cycles;
    @Autowired InterventionWorkflow workflow;
    @Autowired PlanExecutionApplicationService service;
    @Autowired PlanExecutionStore store;
    @Autowired IdempotentActionExecutionService execution;
    @Autowired ExecutableActionCatalog catalog;
    @Autowired ExecutionAttemptService attempts;
    @Autowired ExecutionEvidenceService evidence;
    @Autowired ExecutionTestConfiguration.TestAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    /** Instala identidad para cada caso y restaura los hooks del destinatario simulado. */
    @BeforeEach void setUp() { authenticate("AUTHOR"); adapter.beforeEffect=()->{}; adapter.afterEffect=()->{}; }
    /** No propaga permisos ni comportamiento inyectado hacia la siguiente prueba. */
    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); adapter.beforeEffect=()->{}; adapter.afterEffect=()->{}; }
    /** Simula el Authentication confiable para pruebas de la fachada sin HTTP. */
    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(role.equals("AUTHOR")?"author":"approver",null,
                List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    /** Versiones actuales para reservar el plan aprobado. */
    private Start command(InterventionViews.Plan plan) { return new Start(cycles.getCycle(plan.cycleId()).version(),plan.version(),"ejecución aprobada"); }
    /** Vence exclusivamente la coordinación de esta prueba; no simula ausencia del efecto ni altera el reloj. */
    private void expire(UUID run) { jdbc.update("UPDATE plan_execution SET lease_expires_at=? WHERE run_id=?",Timestamp.from(Instant.now().minusSeconds(1)),run); }

    /** Varias acciones alcanzan IR-009 una sola vez y conservan E07 hasta el incremento de finalización. */
    @Test void shouldExecuteApprovedStepsWithoutDuplicatingEffects() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,2,true); int before=adapter.effects.get();
        var run=service.start(plan.cycleId(),plan.planId(),command(plan));
        assertEquals("APPLIED",run.state()); assertEquals("E07_EJECUTANDO",run.cycleState()); assertFalse(run.requiresReview());
        assertEquals(2,adapter.effects.get()-before);
        assertTrue(run.steps().stream().allMatch(s->s.state().equals("APPLIED")&&s.observedOutcome().equals("APPLIED")));
        for(var step:run.steps()) {
            assertEquals(1,attempts.findHistory(plan.planId(),step.actionId()).size());
            assertEquals("author",attempts.findHistory(plan.planId(),step.actionId()).getFirst().actorId());
            assertEquals(step.operationKey(),ExecutionOperationRegistry.operationKey(plan.planId(),step.actionId()));
        }
        assertThrows(LifecycleException.class,()->service.start(plan.cycleId(),plan.planId(),command(plan)));
        assertEquals(2,adapter.effects.get()-before);
    }

    /** Perder la respuesta después del efecto bloquea los pasos siguientes; recuperar no reenvía UNKNOWN. */
    @Test void shouldStopOnLostResponseAndNeverBlindlyReplay() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,2,true);int before=adapter.effects.get();
        adapter.afterEffect=()->{throw new IllegalStateException("respuesta perdida de test");};
        var run=service.start(plan.cycleId(),plan.planId(),command(plan));
        assertEquals("RECOVERY_REQUIRED",run.state()); assertEquals("E13_RECUPERANDO",run.cycleState());
        assertEquals("UNKNOWN",run.steps().getFirst().observedOutcome()); assertEquals("READY",run.steps().get(1).state());
        assertEquals(1,adapter.effects.get()-before);
        assertThrows(LifecycleException.class,()->service.resume(plan.planId(),new Control(run.version(),"no reenviar")));
        authenticate("APPROVER");
        var recovered=service.recover(plan.planId(),new Control(run.version(),"inspeccionar hechos"));
        assertEquals("RECOVERY_REQUIRED",recovered.state()); assertEquals(1,adapter.effects.get()-before);
        assertEquals(1,attempts.findHistory(plan.planId(),plan.actions().getFirst().actionId()).size());
    }

    /** Un commit IR-009 seguido de caída de coordinación se recupera sin permiso EXECUTE_ACTION vigente. */
    @Test void shouldRecoverCommittedResultAfterPermissionExpiresWithoutExecutingAgain() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);int before=adapter.effects.get();
        var claim=store.reserve(plan.cycleId(),plan.planId(),command(plan),"author");
        var dispatch=store.beginNext(claim);
        execution.execute(new AuthorizedActionExecutionService.ExecutionCommand(dispatch.action().actionId(),plan.planId(),plan.cycleId(),
                dispatch.authorizationId(),dispatch.action().actionType(),"author"),catalog.resolve(dispatch.action().execution()));
        // No store.applied: reproduce la caída entre el commit IR-009 y su proyección.
        expire(claim.runId()); jdbc.update("UPDATE authorizations SET expires_at=? WHERE plan_id=?",Timestamp.from(Instant.now().minusSeconds(1)),plan.planId());
        var pending=service.get(plan.planId()); assertTrue(pending.requiresReview());
        authenticate("APPROVER");
        var recovered=service.recover(plan.planId(),new Control(pending.version(),"recuperar commit confirmado"));
        assertEquals("APPLIED",recovered.state()); assertEquals("E07_EJECUTANDO",recovered.cycleState());
        assertEquals(1,adapter.effects.get()-before); assertEquals(1,attempts.findHistory(plan.planId(),dispatch.action().actionId()).size());
        assertEquals("approver",recovered.updatedBy());
    }

    /** La pérdida de propiedad impide avanzar a la segunda acción aun cuando la primera haya aplicado. */
    @Test void shouldNotAdvanceAfterCoordinatorLeaseIsLostDuringEffect() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,2,true);int before=adapter.effects.get();
        adapter.beforeEffect=()->jdbc.update("UPDATE plan_execution SET lease_expires_at=? WHERE plan_id=?",Timestamp.from(Instant.now().minusSeconds(1)),plan.planId());
        var run=service.start(plan.cycleId(),plan.planId(),command(plan));
        assertTrue(run.requiresReview()); assertEquals(1,adapter.effects.get()-before); assertEquals("READY",run.steps().get(1).state());
        authenticate("APPROVER");
        var recovered=service.recover(plan.planId(),new Control(run.version(),"revisar parcial"));
        assertEquals("APPLIED",recovered.steps().getFirst().state()); assertEquals("RECOVERY_REQUIRED",recovered.state());
        assertEquals(1,adapter.effects.get()-before);
    }

    /** La autorización se revalida para cada acción y una revocación detiene lo pendiente. */
    @Test void shouldRevalidateAuthorizationBetweenActions() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,2,true);int before=adapter.effects.get();
        adapter.afterEffect=()->jdbc.update("UPDATE authorizations SET status='REVOKED',revoked_at=? WHERE plan_id=? AND action_id=?",
                Timestamp.from(Instant.now()),plan.planId(),plan.actions().get(1).actionId());
        var run=service.start(plan.cycleId(),plan.planId(),command(plan));
        assertEquals("RECOVERY_REQUIRED",run.state()); assertEquals(1,adapter.effects.get()-before);
        assertTrue(attempts.findHistory(plan.planId(),plan.actions().get(1).actionId()).isEmpty());
    }

    /** Dos llamadas simultáneas no adquieren dos coordinadores ni producen dos efectos. */
    @Test void shouldRejectConcurrentStartWhileFirstEffectRuns() throws Exception {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);var command=command(plan);int before=adapter.effects.get();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        adapter.beforeEffect=()->{entered.countDown();try {if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}};
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->{authenticate("AUTHOR");try{return service.start(plan.cycleId(),plan.planId(),command);}finally{SecurityContextHolder.clearContext();}});
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                assertEquals("PLAN_EXECUTION_EXISTS",assertThrows(LifecycleException.class,()->service.start(plan.cycleId(),plan.planId(),command)).code());
            } finally { release.countDown(); }
            assertEquals("APPLIED",first.get(15,TimeUnit.SECONDS).state());
        }
        assertEquals(1,adapter.effects.get()-before);
    }

    /** Un reporte contradictorio posterior hace visible requiresReview e impide recuperar un falso éxito. */
    @Test void shouldExposeContradictoryEvidenceAfterAppliedRun() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);
        var run=service.start(plan.cycleId(),plan.planId(),command(plan));
        var attempt=attempts.findHistory(plan.planId(),run.steps().getFirst().actionId()).getFirst();
        evidence.report(UUID.randomUUID(),attempt.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradicción de test",Instant.now().minusSeconds(1));
        var current=service.get(plan.planId()); assertTrue(current.requiresReview()); assertEquals("CONFLICT",current.steps().getFirst().observedOutcome());
        authenticate("APPROVER");
        assertEquals("RECOVERY_REQUIRED",service.recover(plan.planId(),new Control(current.version(),"revisar contradicción")).state());
    }

    /** La fachada protege roles y rechaza transacciones exteriores antes de cualquier reserva. */
    @Test void shouldEnforceRolesAndRejectOuterTransaction() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);
        authenticate("APPROVER"); assertThrows(LifecycleException.class,()->service.start(plan.cycleId(),plan.planId(),command(plan)));
        authenticate("AUTHOR"); assertThrows(LifecycleException.class,()->service.recover(plan.planId(),new Control(0L,"no")));
        var transaction=new TransactionTemplate(transactions);
        assertEquals("OUTER_TRANSACTION_NOT_ALLOWED",assertThrows(LifecycleException.class,()->transaction.execute(status->service.start(plan.cycleId(),plan.planId(),command(plan)))).code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM plan_execution WHERE plan_id=?",Integer.class,plan.planId()));
        SecurityContextHolder.clearContext(); assertThrows(LifecycleException.class,()->service.catalog()); assertThrows(LifecycleException.class,()->service.get(plan.planId()));
    }
}
