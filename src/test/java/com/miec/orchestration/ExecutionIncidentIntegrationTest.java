package com.miec.orchestration;

import com.miec.action.*;
import com.miec.execution.ExecutableActionCatalog;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.miec.orchestration.PlanExecutionContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Incidentes reales sin secretos, con fases verificables y fallos secundarios de auditoría conservados. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:execution_incident_test;DB_CLOSE_DELAY=-1")
class ExecutionIncidentIntegrationTest {
    @Autowired LifecycleService cycles;
    @Autowired InterventionWorkflow workflow;
    @Autowired PlanExecutionApplicationService service;
    @Autowired ExecutionTestConfiguration.TestAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotentActionExecutionService execution;
    @Autowired ExecutableActionCatalog catalog;
    @MockitoSpyBean ExecutionAttemptService attempts;

    /** Instala un autor y limpia hooks de efectos controlados. */
    @BeforeEach void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("author",null,List.of(new SimpleGrantedAuthority("ROLE_AUTHOR"))));
        adapter.duringLookup=()->{};adapter.beforeEffect=()->{};adapter.afterEffect=()->{};
    }
    /** No propaga stubs o identidades a otro escenario. */
    @AfterEach void tearDown() {
        reset(attempts);adapter.duringLookup=()->{};adapter.beforeEffect=()->{};adapter.afterEffect=()->{};SecurityContextHolder.clearContext();
    }
    /** Ejecuta con versiones recién consultadas. */
    private Run start(InterventionViews.Plan plan) { return service.start(plan.cycleId(),plan.planId(),new Start(cycles.getCycle(plan.cycleId()).version(),plan.version(),"probar incidente")); }
    /** Vence el permiso dentro de lookup sin afectar propiedad o relojes globales. */
    private void expire(InterventionViews.Plan plan) { jdbc.update("UPDATE authorizations SET expires_at=? WHERE plan_id=?",Timestamp.from(Instant.now().minusSeconds(1)),plan.planId()); }

    /** Rechazar antes del envío conserva correlación y no afirma ausencia global de efecto de la operación. */
    @Test void shouldRecordTrustedRejectionAndPropagateItsIncidentReference() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);int before=adapter.effects.get();adapter.duringLookup=()->expire(plan);
        var run=start(plan);assertEquals(0,adapter.effects.get()-before);assertEquals(2,run.incidents().size());
        var core=run.incidents().stream().filter(i->i.origin()==ExecutionIncidentService.Origin.CORE).findFirst().orElseThrow();
        var coordination=run.incidents().stream().filter(i->i.origin()==ExecutionIncidentService.Origin.COORDINATOR).findFirst().orElseThrow();
        assertEquals("AUTHORIZATION_INVALID",core.code());assertEquals(ExecutionIncidentService.Phase.REJECTED_BEFORE_SEND,core.phase());
        assertEquals(core.incidentId(),coordination.relatedIncidentId());assertEquals(run.planId(),core.planId());assertEquals(run.cycleId(),core.cycleId());
        assertEquals(run.steps().getFirst().actionId(),core.actionId());assertEquals(run.steps().getFirst().executionId(),core.executionId());
        assertEquals("UNKNOWN",run.steps().getFirst().observedOutcome());
    }

    /** Un fallo posterior al efecto es incierto; el contenido de su excepción no aparece en el DTO. */
    @Test void shouldKeepPostSendFailureUncertainAndSanitizeIncidentView() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);int before=adapter.effects.get();
        adapter.afterEffect=()->{throw new IllegalStateException("TEST_SECRET SQL jdbc:private complete stack trace");};
        var run=start(plan);assertEquals(1,adapter.effects.get()-before);
        assertTrue(run.incidents().stream().allMatch(i->i.phase()==ExecutionIncidentService.Phase.OUTCOME_UNCERTAIN));
        assertEquals("UNKNOWN",run.steps().getFirst().observedOutcome());
        String encoded=JsonMapper.builder().build().writeValueAsString(run);
        assertFalse(encoded.contains("TEST_SECRET"));assertFalse(encoded.contains("jdbc:private"));assertFalse(encoded.contains("stack trace"));
    }

    /** Si complete y markUnknown fallan, conserva el primario, el secundario y la fase de efecto ya observado. */
    @Test void shouldPreserveSuppressedPersistenceFailureAfterAppliedEffect() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);int before=adapter.effects.get();
        var primary=new IllegalStateException("completion failure");var secondary=new IllegalStateException("unknown write failure");
        doThrow(primary).when(attempts).completeAttempt(any(),any());doThrow(secondary).when(attempts).markUnknown(any(),anyString());
        var run=start(plan);
        assertEquals(1,adapter.effects.get()-before);assertEquals("RECOVERY_REQUIRED",run.state());
        assertTrue(List.of(primary.getSuppressed()).contains(secondary));
        assertTrue(run.incidents().stream().allMatch(i->i.phase()==ExecutionIncidentService.Phase.APPLIED_CONFIRMATION_FAILED));
        assertTrue(run.incidents().stream().anyMatch(i->i.secondaryFailureCount()>0));
        assertEquals(ExecutionRetryPolicy.ExecutionStatus.IN_PROGRESS,attempts.findHistory(plan.planId(),plan.actions().getFirst().actionId()).getFirst().status());
    }

    /** Un adaptador no puede reutilizar una excepción de rechazo para fingir que no entró en execute. */
    @Test void shouldNotTrustRejectionExceptionThrownAfterEffect() {
        var rejectedPlan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);var firstAction=rejectedPlan.actions().getFirst();
        UUID authorization=jdbc.queryForObject("SELECT authorization_id FROM authorizations WHERE plan_id=?",UUID.class,rejectedPlan.planId());
        adapter.duringLookup=()->expire(rejectedPlan);
        var rejected=assertThrows(ExecutionDispatchRejectedException.class,()->execution.execute(new AuthorizedActionExecutionService.ExecutionCommand(
                firstAction.actionId(),rejectedPlan.planId(),rejectedPlan.cycleId(),authorization,"PROCESS_EXECUTION","author"),catalog.resolve(firstAction.execution())));
        adapter.duringLookup=()->{};
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);int before=adapter.effects.get();adapter.afterEffect=()->{throw rejected;};
        var run=start(plan);assertEquals(1,adapter.effects.get()-before);
        assertTrue(run.incidents().stream().allMatch(i->i.phase()==ExecutionIncidentService.Phase.OUTCOME_UNCERTAIN));
    }

    /** Si no puede auditarse el incidente, no devuelve un éxito de auditoría ni descarta las causas originales. */
    @Test void shouldExposeCorrelationWhenIncidentStorageFails() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);adapter.duringLookup=()->expire(plan);int before=adapter.effects.get();
        jdbc.execute("ALTER TABLE execution_incident ADD CONSTRAINT test_incident_failure CHECK(plan_id<>CAST('"+plan.planId()+"' AS UUID))");
        try {
            var failure=assertThrows(ExecutionIncidentAuditException.class,()->start(plan));
            assertNotNull(failure.incidentId());assertInstanceOf(ExecutionDispatchRejectedException.class,failure.getCause());
            assertTrue(failure.getCause().getSuppressed().length>0);assertEquals(0,adapter.effects.get()-before);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM execution_incident WHERE plan_id=?",Integer.class,plan.planId()));
            assertEquals("RECOVERY_REQUIRED",service.get(plan.planId()).state());
        } finally { jdbc.execute("ALTER TABLE execution_incident DROP CONSTRAINT test_incident_failure"); }
    }

    /** Rechazar el paso pendiente no borra ni reclasifica como fallido el efecto previo ya aplicado. */
    @Test void shouldRecordPendingStepRejectionWithoutHidingEarlierAppliedEffect() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,2,true);
        adapter.afterEffect=()->jdbc.update("UPDATE authorizations SET status='REVOKED',revoked_at=? WHERE plan_id=? AND action_id=?",
                Timestamp.from(Instant.now()),plan.planId(),plan.actions().get(1).actionId());
        var run=start(plan);assertEquals("APPLIED",run.steps().getFirst().observedOutcome());assertEquals("READY",run.steps().get(1).state());
        assertTrue(run.incidents().stream().anyMatch(i->i.code().equals("PLAN_ADMISSION_REJECTED")&&i.phase()==ExecutionIncidentService.Phase.REJECTED_BEFORE_SEND&&i.executionId()==null));
    }

    /** V014 conserva un intento histórico y agrega únicamente restricciones de contexto y tabla vacía. */
    @Test void shouldMigrateV013WithoutChangingExistingAttempt() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:incident_upgrade_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("013").load().migrate();var sql=new JdbcTemplate(source);
            UUID system=UUID.randomUUID(),cycle=UUID.randomUUID(),plan=UUID.randomUUID(),action=UUID.randomUUID(),authorization=UUID.randomUUID(),attempt=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'legacy')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            sql.update("INSERT INTO intervention_plan(plan_id,cycle_id,status) VALUES (?,?,'APPROVED')",plan,cycle);
            sql.update("INSERT INTO authorizations(authorization_id,plan_id,action_id,requested_by,scope,status,issued_at,approved_by) VALUES (?,?,?,'author','EXECUTE_ACTION','APPROVED',CURRENT_TIMESTAMP,'approver')",authorization,plan,action);
            sql.update("INSERT INTO execution_attempt(execution_id,planned_action_id,plan_id,cycle_id,authorization_id,attempt_number,execution_status,effect_outcome,actor_id) VALUES (?,?,?,?,?,1,'PENDING','EFFECT_UNKNOWN','author')",attempt,action,plan,cycle,authorization);
            var before=sql.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",attempt);
            assertEquals(1,Flyway.configure().dataSource(source).target("014").load().migrate().migrationsExecuted);
            assertEquals(before,sql.queryForMap("SELECT * FROM execution_attempt WHERE execution_id=?",attempt));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM execution_incident",Integer.class));
        } finally { source.destroy(); }
    }
}
