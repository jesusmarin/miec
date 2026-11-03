package com.miec.orchestration;

import com.miec.authorization.AuthorizationPersistenceService;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static com.miec.orchestration.InterventionCommands.*;
import static org.junit.jupiter.api.Assertions.*;

/** Decisiones atómicas, exclusión mutua y permisos que solo se activan al completar el plan. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:intervention_decisions;DB_CLOSE_DELAY=-1")
class InterventionDecisionTest {
    @Autowired private LifecycleService cycles;
    @Autowired private InterventionWorkflow workflow;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthorizationPersistenceService validity;
    @Autowired private com.miec.action.ExecutionAttemptService attempts;

    /** Planes independientes con solicitudes de todas sus acciones. */
    private InterventionViews.Plan requested(int actions) {
        var prepared=InterventionTestSupport.prepare(cycles,workflow);
        var plan=workflow.propose(prepared.cycle().cycleId(),InterventionTestSupport.proposal(prepared,actions),"author");
        for(var action:plan.actions()) {
            var current=workflow.getPlan(plan.planId());
            workflow.requestAuthorization(plan.planId(),new RequestAuthorization(current.version(),action.actionId(),600L),"author");
        }
        return workflow.getPlan(plan.planId());
    }

    /** Obtiene la solicitud de una acción para comprobarla mediante la política productiva IR-010. */
    private InterventionViews.Authorization request(InterventionViews.Plan plan,int index) {
        UUID id=jdbc.queryForObject("SELECT authorization_id FROM authorizations WHERE plan_id=? AND action_id=?",UUID.class,plan.planId(),plan.actions().get(index).actionId());
        return workflow.getAuthorization(id);
    }

    /** Consulta la política existente; una decisión histórica no basta para permitir ejecución. */
    private boolean allowed(InterventionViews.Authorization request) {
        return validity.evaluate(request.authorizationId(),new AuthorizationUseRequest(request.planId(),request.actionId(),"EXECUTE_ACTION",Instant.now())).allowed();
    }

    /** Aprobaciones parciales quedan inactivas hasta que todas sean válidas y el ciclo llegue a E06. */
    @Test void shouldActivateOnlyWhenEntirePlanIsApproved() {
        var plan=requested(2);var first=request(plan,0);var second=request(plan,1);
        var partial=workflow.decide(first.authorizationId(),new Decide(0L,"revisada"),true,"reviewer");
        assertEquals("APPROVED_PENDING_PLAN",partial.status()); assertFalse(allowed(partial));
        assertThrows(com.miec.action.ExecutionAttemptService.AttemptDeniedException.class,()->attempts.createAttempt(
                new com.miec.action.ExecutionAttemptService.CreateAttemptCommand(first.actionId(),plan.planId(),plan.cycleId(),first.authorizationId(),"PROCESS_EXECUTION","author")));
        assertEquals("E05_ESPERANDO_AUTORIZACION",cycles.getCycle(plan.cycleId()).state());
        var complete=workflow.decide(second.authorizationId(),new Decide(0L,"plan completo"),true,"reviewer");
        assertEquals("APPROVED",complete.status());
        assertTrue(allowed(complete)); assertTrue(allowed(workflow.getAuthorization(first.authorizationId())));
        assertEquals("APPROVED",workflow.getPlan(plan.planId()).status());
        assertEquals("E06_PREPARADO",cycles.getCycle(plan.cycleId()).state());
        assertEquals("reviewer",complete.decision().decidedBy());
        assertEquals(com.miec.action.ExecutionRetryPolicy.ExecutionStatus.PENDING,attempts.createAttempt(
                new com.miec.action.ExecutionAttemptService.CreateAttemptCommand(complete.actionId(),plan.planId(),plan.cycleId(),complete.authorizationId(),"PROCESS_EXECUTION","author")).status());
    }

    /** Rechazar una acción cancela permisos relacionados sin borrar quién aprobó previamente. */
    @Test void shouldRejectAndPreserveEarlierHumanDecision() {
        var plan=requested(2);var first=request(plan,0);var second=request(plan,1);
        workflow.decide(first.authorizationId(),new Decide(0L,"primera revisada"),true,"reviewer");
        var rejected=workflow.decide(second.authorizationId(),new Decide(0L,"riesgo no aceptado"),false,"reviewer");
        var cancelled=workflow.getAuthorization(first.authorizationId());
        assertEquals("REJECTED",rejected.status()); assertEquals("CANCELLED",cancelled.status());
        assertEquals("APPROVED",cancelled.decision().decision()); assertFalse(allowed(cancelled));
        assertEquals("E12_SUSPENDIDO",cycles.getCycle(plan.cycleId()).state());
        assertEquals("REJECTED",workflow.getPlan(plan.planId()).status());
    }

    /** Plan modificado, solicitante como aprobador y solicitud vencida no producen decisión. */
    @Test void shouldRejectChangedPlanSelfApprovalAndExpiredRequest() {
        var plan=requested(1);var request=request(plan,0);
        assertEquals("SELF_APPROVAL_FORBIDDEN",assertThrows(LifecycleException.class,
                ()->workflow.decide(request.authorizationId(),new Decide(0L,"no"),true,"author")).code());
        assertEquals("SELF_APPROVAL_FORBIDDEN",assertThrows(LifecycleException.class,
                ()->workflow.decide(request.authorizationId(),new Decide(0L,"no"),true,"AUTHOR")).code());
        jdbc.update("UPDATE intervention_plan SET rollback_strategy='cambiada' WHERE plan_id=?",plan.planId());
        assertEquals("PLAN_CHANGED",assertThrows(LifecycleException.class,
                ()->workflow.decide(request.authorizationId(),new Decide(0L,"no"),true,"reviewer")).code());
        assertEquals("PENDING",workflow.getAuthorization(request.authorizationId()).status());
        var expiredPlan=requested(1);var expired=request(expiredPlan,0);
        jdbc.update("UPDATE authorizations SET expires_at=? WHERE authorization_id=?",Timestamp.from(Instant.now().minusSeconds(1)),expired.authorizationId());
        assertEquals("AUTHORIZATION_EXPIRED",assertThrows(LifecycleException.class,
                ()->workflow.decide(expired.authorizationId(),new Decide(0L,"no"),true,"reviewer")).code());
        assertEquals("REJECTED",workflow.decide(expired.authorizationId(),new Decide(0L,"cerrar vencida"),false,"reviewer").status());
    }

    /** Una aprobación anterior vencida impide activar el plan y revierte la última decisión. */
    @Test void shouldRejectExpiredRelatedApproval() {
        var plan=requested(2);var first=request(plan,0);var second=request(plan,1);
        workflow.decide(first.authorizationId(),new Decide(0L,"primera"),true,"reviewer");
        jdbc.update("UPDATE authorizations SET expires_at=? WHERE authorization_id=?",Timestamp.from(Instant.now().minusSeconds(1)),first.authorizationId());
        assertEquals("AUTHORIZATION_WINDOW_INVALID",assertThrows(LifecycleException.class,
                ()->workflow.decide(second.authorizationId(),new Decide(0L,"segunda"),true,"reviewer")).code());
        assertEquals("PENDING",workflow.getAuthorization(second.authorizationId()).status());
        assertNull(workflow.getAuthorization(second.authorizationId()).decision());
    }

    /** Aprobar y rechazar simultáneamente crean una única decisión y un conflicto, no estados contradictorios. */
    @Test void shouldSerializeOpposingDecisions() throws Exception {
        var plan=requested(1);var request=request(plan,0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            java.util.function.Function<Boolean,Callable<String>> task=approve->()->{
                barrier.await(5,TimeUnit.SECONDS);
                try{workflow.decide(request.authorizationId(),new Decide(0L,"revisión"),approve,"reviewer");return "DECIDED";}
                catch(LifecycleException failure){return failure.code();}
            };
            var first=pool.submit(task.apply(true));var second=pool.submit(task.apply(false));
            assertEquals(java.util.Set.of("DECIDED","AUTHORIZATION_VERSION_CONFLICT"),java.util.Set.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM authorization_decision WHERE authorization_id=?",Integer.class,request.authorizationId()));
    }

    /** Una falla de la última escritura revierte activación, estado, versión y todos los eventos del comando. */
    @Test void shouldRollbackApprovalWhenDecisionCannotBeStored() {
        var plan=requested(1);var request=request(plan,0);var before=cycles.getCycle(plan.cycleId());
        int events=jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=?",Integer.class,plan.cycleId());
        String actor=UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE authorization_decision ADD CONSTRAINT mvp02_decision_failure CHECK(decided_by <> '"+actor+"')");
        try {
            assertThrows(DataIntegrityViolationException.class,()->workflow.decide(request.authorizationId(),new Decide(0L,"fallará"),true,actor));
        } finally { jdbc.execute("ALTER TABLE authorization_decision DROP CONSTRAINT mvp02_decision_failure"); }
        assertEquals(request,workflow.getAuthorization(request.authorizationId()));
        assertEquals(plan,workflow.getPlan(plan.planId())); assertEquals(before,cycles.getCycle(plan.cycleId()));
        assertEquals(events,jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=?",Integer.class,plan.cycleId()));
    }
}
