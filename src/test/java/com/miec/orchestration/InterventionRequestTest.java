package com.miec.orchestration;

import com.miec.authorization.AuthorizationPersistenceService;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static com.miec.orchestration.InterventionCommands.*;
import static org.junit.jupiter.api.Assertions.*;

/** Solicitudes ligadas al snapshot del plan, sin conceder ejecución por estar pendientes. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:intervention_requests;DB_CLOSE_DELAY=-1")
class InterventionRequestTest {
    @Autowired private LifecycleService cycles;
    @Autowired private InterventionWorkflow workflow;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthorizationPersistenceService validity;

    /** Prepara una propuesta con dos acciones del contrato existente. */
    private InterventionViews.Plan plan() {
        var prepared=InterventionTestSupport.prepare(cycles,workflow);
        return workflow.propose(prepared.cycle().cycleId(),InterventionTestSupport.proposal(prepared,2),"author");
    }

    /** La solicitud pendiente conserva alcance, plazo y huella pero IR-010 no permite utilizarla. */
    @Test void shouldRequestPermissionWithoutGrantingExecution() {
        var plan=plan(); var action=plan.actions().getFirst().actionId();
        var request=workflow.requestAuthorization(plan.planId(),new RequestAuthorization(0L,action,600L),"author");
        assertEquals("PENDING",request.status()); assertNull(request.approvedBy()); assertNull(request.decision());
        assertEquals("EXECUTE_ACTION",request.scope());
        assertEquals(600,java.time.Duration.between(request.issuedAt(),request.expiresAt()).getSeconds());
        assertEquals(plan.fingerprint(),jdbc.queryForObject("SELECT request_fingerprint FROM authorizations WHERE authorization_id=?",String.class,request.authorizationId()));
        assertFalse(validity.evaluate(request.authorizationId(),new AuthorizationUseRequest(plan.planId(),action,"EXECUTE_ACTION",Instant.now())).allowed());
        assertEquals("E05_ESPERANDO_AUTORIZACION",cycles.getCycle(plan.cycleId()).state());
        assertEquals(1,workflow.getPlan(plan.planId()).version());
    }

    /** Duplicados, acciones ajenas, plazos inválidos y una revisión vieja se rechazan sin otra solicitud. */
    @Test void shouldRejectInvalidAndDuplicateRequests() {
        var plan=plan(); var action=plan.actions().getFirst().actionId();
        assertThrows(LifecycleException.class,()->workflow.requestAuthorization(plan.planId(),new RequestAuthorization(0L,UUID.randomUUID(),600L),"author"));
        assertThrows(LifecycleException.class,()->workflow.requestAuthorization(plan.planId(),new RequestAuthorization(0L,action,1L),"author"));
        workflow.requestAuthorization(plan.planId(),new RequestAuthorization(0L,action,600L),"author");
        assertEquals("PLAN_VERSION_CONFLICT",assertThrows(LifecycleException.class,()->workflow.requestAuthorization(plan.planId(),new RequestAuthorization(0L,action,600L),"author")).code());
        assertEquals("AUTHORIZATION_EXISTS",assertThrows(LifecycleException.class,()->workflow.requestAuthorization(plan.planId(),new RequestAuthorization(1L,action,600L),"author")).code());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE plan_id=?",Integer.class,plan.planId()));
    }

    /** Dos solicitudes con la misma revisión tienen un ganador y un conflicto de versión. */
    @Test void shouldSerializeConcurrentRequests() throws Exception {
        var plan=plan(); var action=plan.actions().getFirst().actionId();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<String> task=()->{barrier.await(5,TimeUnit.SECONDS);try{workflow.requestAuthorization(plan.planId(),new RequestAuthorization(0L,action,600L),"author");return "CREATED";}catch(LifecycleException failure){return failure.code();}};
            var first=pool.submit(task);var second=pool.submit(task);
            assertEquals(java.util.Set.of("CREATED","PLAN_VERSION_CONFLICT"),java.util.Set.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));
        }
    }

    /** Una auditoría fallida revierte la solicitud y las revisiones de ciclo y plan. */
    @Test void shouldRollbackRequestIfAuditFails() {
        var plan=plan(); var before=cycles.getCycle(plan.cycleId()); String actor=UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT mvp02_request_failure CHECK(actor_id <> '"+actor+"')");
        try {
            assertThrows(DataIntegrityViolationException.class,()->workflow.requestAuthorization(plan.planId(),new RequestAuthorization(0L,plan.actions().getFirst().actionId(),600L),actor));
        } finally { jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT mvp02_request_failure"); }
        assertEquals(plan,workflow.getPlan(plan.planId())); assertEquals(before,cycles.getCycle(plan.cycleId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE plan_id=?",Integer.class,plan.planId()));
    }
}
