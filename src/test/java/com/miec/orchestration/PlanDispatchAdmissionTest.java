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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import static com.miec.orchestration.PlanExecutionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cambios durante lookup: la admisión final debe impedir el callback de envío sin inferir ausencia global de efecto. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:plan_dispatch_admission;DB_CLOSE_DELAY=-1")
class PlanDispatchAdmissionTest {
    @Autowired LifecycleService cycles;
    @Autowired InterventionWorkflow workflow;
    @Autowired PlanExecutionApplicationService service;
    @Autowired ExecutionTestConfiguration.TestAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotentActionExecutionService execution;
    @Autowired ExecutableActionCatalog catalog;
    @Autowired ExecutionAttemptService attempts;

    /** Identidad del servidor para invocar la fachada y hooks sin efectos residuales. */
    @BeforeEach void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("author",null,List.of(new SimpleGrantedAuthority("ROLE_AUTHOR"))));
        resetHooks();
    }
    /** No propaga permisos ni hooks a otras pruebas. */
    @AfterEach void tearDown() { resetHooks();SecurityContextHolder.clearContext(); }
    /** Limpia únicamente comportamiento simulado; cada plan conserva IDs y ledger independientes. */
    private void resetHooks() { adapter.beforeEffect=()->{};adapter.afterEffect=()->{};adapter.duringLookup=()->{}; }
    /** Ejecuta un plan cuyo permiso o snapshot cambia dentro de la consulta sin efectos. */
    private Run rejected(Consumer<InterventionViews.Plan> change) {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);int before=adapter.effects.get();
        adapter.duringLookup=()->change.accept(plan);
        var result=service.start(plan.cycleId(),plan.planId(),new Start(cycles.getCycle(plan.cycleId()).version(),plan.version(),"admisión"));
        assertEquals(0,adapter.effects.get()-before);assertEquals("RECOVERY_REQUIRED",result.state());
        assertNotEquals("APPLIED",result.steps().getFirst().observedOutcome());
        return result;
    }
    /** Vencer el permiso durante lookup no produce un POST aunque el inicio del intento hubiera sido autorizado. */
    @Test void shouldRejectAuthorizationExpiredDuringLookup() {
        rejected(plan->jdbc.update("UPDATE authorizations SET expires_at=? WHERE plan_id=?",Timestamp.from(Instant.now().minusSeconds(1)),plan.planId()));
    }
    /** Revocar el permiso durante lookup impide enviar la misma acción, no solo la acción siguiente. */
    @Test void shouldRejectAuthorizationRevokedDuringLookup() {
        rejected(plan->jdbc.update("UPDATE authorizations SET status='REVOKED',revoked_at=? WHERE plan_id=?",Timestamp.from(Instant.now()),plan.planId()));
    }
    /** Un alcance sustituido después de iniciar no se interpreta como EXECUTE_ACTION. */
    @Test void shouldRejectScopeChangedDuringLookup() {
        rejected(plan->jdbc.update("UPDATE authorizations SET scope='RECONCILE_ACTION' WHERE plan_id=?",plan.planId()));
    }
    /** El snapshot de operación debe seguir correspondiendo al cuerpo aprobado y al capturado por el adaptador. */
    @Test void shouldRejectOperationBytesChangedDuringLookup() {
        rejected(plan->jdbc.update("UPDATE execution_operation SET request_body='{}' WHERE plan_id=?",plan.planId()));
    }
    /** La ruta IR-009 directa también revalida la huella; no depende de la guarda de la fachada MVP. */
    @Test void shouldRejectApprovedFingerprintChangedDuringLookupInCoreRoute() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);var action=plan.actions().getFirst();int before=adapter.effects.get();
        UUID authorization=jdbc.queryForObject("SELECT authorization_id FROM authorizations WHERE plan_id=?",UUID.class,plan.planId());
        adapter.duringLookup=()->jdbc.update("UPDATE intervention_plan SET rollback_strategy='unapproved' WHERE plan_id=?",plan.planId());
        var command=new AuthorizedActionExecutionService.ExecutionCommand(action.actionId(),plan.planId(),plan.cycleId(),authorization,action.actionType(),"author");
        var denied=assertThrows(ExecutionDispatchRejectedException.class,()->execution.execute(command,catalog.resolve(action.execution())));
        assertEquals(ExecutionDispatchRejectedException.Code.APPROVED_CONTENT_CHANGED,denied.code());
        assertEquals(0,adapter.effects.get()-before);assertEquals(1,attempts.findHistory(plan.planId(),action.actionId()).size());
    }
    /** La validación devuelve sus locks antes de llamar tanto a lookup como a execute. */
    @Test void shouldSendValidRequestWithoutAnOuterDatabaseTransaction() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);int before=adapter.effects.get();
        adapter.duringLookup=()->assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        adapter.beforeEffect=()->assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var result=service.start(plan.cycleId(),plan.planId(),new Start(cycles.getCycle(plan.cycleId()).version(),plan.version(),"válido"));
        assertEquals("APPLIED",result.state());assertEquals(1,adapter.effects.get()-before);
    }

    /** El lease del coordinador no habilita un envío con el intento IR-009 vencido. */
    @Test void shouldRejectExpiredAttemptWhilePlanCoordinatorRemainsCurrent() {
        var run=rejected(plan->jdbc.update("UPDATE execution_attempt SET started_at=?,heartbeat_at=?,lease_expires_at=? WHERE plan_id=?",
                Timestamp.from(Instant.now().minusSeconds(3)),Timestamp.from(Instant.now().minusSeconds(2)),Timestamp.from(Instant.now().minusSeconds(1)),plan.planId()));
        assertEquals("EXPIRED",run.steps().getFirst().observedOutcome());
        var attempt=attempts.findHistory(run.planId(),run.steps().getFirst().actionId()).getFirst();
        assertTrue(attempt.leaseExpiresAt().isBefore(Instant.now()));
        assertEquals(ExecutionRetryPolicy.ExecutionStatus.IN_PROGRESS,attempt.status());
    }

    /** Cambiar el propietario no permite enviar ni finalizar en nombre de la adquisición anterior. */
    @Test void shouldRejectDifferentAttemptOwner() {
        var run=rejected(plan->jdbc.update("UPDATE execution_attempt SET executor_id='replacement-owner' WHERE plan_id=?",plan.planId()));
        assertEquals("replacement-owner",attempts.findHistory(run.planId(),run.steps().getFirst().actionId()).getFirst().executorId());
    }

    /** Un token de fencing diferente invalida la admisión sin apropiación o renovación implícita. */
    @Test void shouldRejectDifferentFencingToken() {
        rejected(plan->jdbc.update("UPDATE execution_attempt SET fencing_token=fencing_token+1 WHERE plan_id=?",plan.planId()));
    }

    /** Un intento que ya salió de IN_PROGRESS no se vuelve a enviar por conservar el objeto del ejecutor. */
    @Test void shouldRejectAttemptNoLongerInProgress() {
        rejected(plan->jdbc.update("UPDATE execution_attempt SET execution_status='UNKNOWN',completed_at=CURRENT_TIMESTAMP WHERE plan_id=?",plan.planId()));
    }
}
