package com.miec.orchestration;

import com.miec.action.*;
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
import static com.miec.orchestration.PlanExecutionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recuperación real con permisos independientes, respuesta perdida, evidencia y ausencia de POST adicionales. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:plan_remote_recovery;DB_CLOSE_DELAY=-1")
class PlanRecoveryApplicationTest {
    @Autowired LifecycleService cycles;
    @Autowired InterventionWorkflow workflow;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PlanRecoveryApplicationService recovery;
    @Autowired ExecutionAttemptService attempts;
    @Autowired ExecutionEvidenceService evidence;
    @Autowired ExecutionReconciliationService decisions;
    @Autowired ExecutionTestConfiguration.TestAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    /** Restablece el destinatario simulado y la identidad autora para cada prueba. */
    @BeforeEach void prepare() { authenticate("AUTHOR"); adapter.afterEffect=()->{};adapter.duringLookup=()->{}; }
    /** Evita compartir hooks o identidad con otras pruebas. */
    @AfterEach void cleanup() { SecurityContextHolder.clearContext();adapter.afterEffect=()->{};adapter.duringLookup=()->{}; }
    /** Crea identidades del servidor; ningún comando admite nombres de actores. */
    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(role.equals("AUTHOR")?"author":"approver",null,
                List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    /** Aplica el primer efecto y pierde su respuesta, conservando UNKNOWN en IR-009. */
    private Run lost(int count) {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,count,true);
        adapter.afterEffect=()->{throw new IllegalStateException("respuesta perdida");};
        var run=execution.start(plan.cycleId(),plan.planId(),new Start(cycles.getCycle(plan.cycleId()).version(),plan.version(),"ejecutar"));
        adapter.afterEffect=()->{};assertEquals("UNKNOWN",run.steps().getFirst().observedOutcome());return run;
    }
    /** Solicita y aprueba el permiso específico mediante los servicios productivos. */
    private UUID permission(Run run) {
        authenticate("AUTHOR");var request=recovery.request(run.planId(),new RequestReconciliation(execution.get(run.planId()).version(),run.steps().getFirst().actionId(),600L,"consultar destinatario"));
        authenticate("APPROVER");return recovery.decide(request.authorizationId(),new InterventionCommands.Decide(0L,"revisar"),true).authorizationId();
    }
    /** Usa la revisión actual del run y la acción persistida. */
    private Reconcile command(Run run,UUID permission) { return new Reconcile(execution.get(run.planId()).version(),run.steps().getFirst().actionId(),permission,"verificar resultado"); }

    /** Un efecto con respuesta perdida se reconcilia sin repetirlo y permite finalizar E13 a E08. */
    @Test void shouldReconcileLostResponseAndFinalizeWithoutSendingAgain() {
        var run=lost(1);var permission=permission(run);int count=adapter.effects.get();
        jdbc.update("UPDATE authorizations SET expires_at=? WHERE plan_id=? AND scope='EXECUTE_ACTION'",Timestamp.from(Instant.now().minusSeconds(1)),run.planId());
        var recovered=recovery.reconcile(run.planId(),command(run,permission));
        assertEquals("APPLIED",recovered.state());assertEquals("E13_RECUPERANDO",recovered.cycleState());assertFalse(recovered.requiresReview());
        var step=recovered.steps().getFirst();assertEquals("UNKNOWN",attempts.findHistory(run.planId(),step.actionId()).getFirst().status().name());
        assertEquals(ExecutionReconciliationPolicy.Resolution.APPLIED,decisions.find(step.executionId()).orElseThrow().command().resolution());
        assertEquals(1,evidence.findByAttempt(step.executionId()).stream().filter(e->e.source()==ExecutionEvidenceService.Source.DESTINATION_VERIFIED).count());
        int reports=evidence.findByAttempt(step.executionId()).size();
        var repeated=recovery.reconcile(run.planId(),command(recovered,permission));assertEquals(reports,evidence.findByAttempt(step.executionId()).size());
        assertEquals("E08_VERIFICANDO",execution.finish(run.planId(),new Finish(repeated.version(),cycles.getCycle(run.cycleId()).version(),"revisión final")).cycleState());
        assertEquals(count,adapter.effects.get());assertEquals(1,attempts.findHistory(run.planId(),step.actionId()).size());
    }

    /** Consultar una acción no despacha las acciones READY ni presume plan completo. */
    @Test void shouldPreservePartialResultAndNeverDispatchRemainingSteps() {
        var run=lost(2);var permission=permission(run);int count=adapter.effects.get();
        var recovered=recovery.reconcile(run.planId(),command(run,permission));
        assertEquals("RECOVERY_REQUIRED",recovered.state());assertEquals("APPLIED",recovered.steps().getFirst().state());
        assertEquals("READY",recovered.steps().get(1).state());assertEquals(count,adapter.effects.get());
        assertThrows(LifecycleException.class,()->execution.finish(run.planId(),new Finish(recovered.version(),cycles.getCycle(run.cycleId()).version(),"incompleto")));
    }

    /** NOT_FOUND conserva incertidumbre y no crea evidencia de ausencia ni decisiones. */
    @Test void shouldKeepNotFoundUncertainWithoutRetry() {
        var run=lost(1);var permission=permission(run);adapter.ledger.remove(run.steps().getFirst().operationKey());int count=adapter.effects.get();
        assertEquals("DESTINATION_NOT_CONFIRMED",assertThrows(LifecycleException.class,()->recovery.reconcile(run.planId(),command(run,permission))).code());
        assertEquals("UNKNOWN",execution.get(run.planId()).steps().getFirst().observedOutcome());assertEquals(count,adapter.effects.get());
        assertTrue(decisions.find(run.steps().getFirst().executionId()).isEmpty());
    }

    /** Vencimiento o revocación durante GET impiden guardar evidencia verificada y resolver el intento. */
    @Test void shouldRevalidatePermissionAfterNetwork() {
        for(boolean revoke:List.of(false,true)) {
            var run=lost(1);var permission=permission(run);
            adapter.duringLookup=()-> {
                // La prueba exige vencimiento inequívoco, no una frontera dependiente
                // del redondeo de CURRENT_TIMESTAMP frente al reloj de vigencia.
                var expired=jdbc.queryForObject("SELECT miec_wall_clock()",(rs,row)->rs.getTimestamp(1).toInstant()).minusSeconds(1);
                assertEquals(1,jdbc.update(revoke?"UPDATE authorizations SET status='REVOKED',revoked_at=? WHERE authorization_id=?":"UPDATE authorizations SET expires_at=? WHERE authorization_id=?",Timestamp.from(expired),permission));
            };
            assertThrows(LifecycleException.class,()->recovery.reconcile(run.planId(),command(run,permission)));
            assertTrue(evidence.findByAttempt(run.steps().getFirst().executionId()).stream().noneMatch(e->e.source()==ExecutionEvidenceService.Source.DESTINATION_VERIFIED));assertTrue(decisions.find(run.steps().getFirst().executionId()).isEmpty());
            adapter.duringLookup=()->{};authenticate("AUTHOR");
        }
    }

    /** Evidencia contraria recibida durante GET bloquea la resolución aunque el destinatario responda APPLIED. */
    @Test void shouldRejectContradictionArrivingDuringLookup() {
        var run=lost(1);var permission=permission(run);var attempt=attempts.findHistory(run.planId(),run.steps().getFirst().actionId()).getFirst();
        adapter.duringLookup=()->evidence.report(UUID.randomUUID(),attempt.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"reporte contradictorio",Instant.now().minusSeconds(1));
        assertThrows(LifecycleException.class,()->recovery.reconcile(run.planId(),command(run,permission)));
        assertTrue(decisions.find(attempt.executionId()).isEmpty());assertTrue(execution.get(run.planId()).requiresReview());
    }

    /** El permiso de ejecución, versiones antiguas y una transacción exterior no habilitan consultas. */
    @Test void shouldRejectWrongPermissionStaleVersionRolesAndOuterTransaction() {
        var run=lost(1);authenticate("APPROVER");var wrong=run.steps().getFirst().authorizationId();
        assertEquals("RECONCILIATION_AUTHORIZATION_INVALID",assertThrows(LifecycleException.class,()->recovery.reconcile(run.planId(),command(run,wrong))).code());
        var permission=permission(run);var current=command(run,permission);
        assertEquals("RUN_VERSION_CONFLICT",assertThrows(LifecycleException.class,()->recovery.reconcile(run.planId(),new Reconcile(0L,current.actionId(),permission,"viejo"))).code());
        assertEquals("OUTER_TRANSACTION_NOT_ALLOWED",assertThrows(LifecycleException.class,()->new TransactionTemplate(transactions).execute(tx->recovery.reconcile(run.planId(),current))).code());
        authenticate("AUTHOR");assertThrows(LifecycleException.class,()->recovery.reconcile(run.planId(),current));
        authenticate("APPROVER");assertThrows(LifecycleException.class,()->recovery.request(run.planId(),new RequestReconciliation(current.expectedVersion(),current.actionId(),600L,"no")));
    }

    /** Un rechazo queda auditado y la solicitud no puede aprobarse posteriormente. */
    @Test void shouldKeepRejectedAuthorizationAndPreventSelfApproval() {
        var run=lost(1);var request=recovery.request(run.planId(),new RequestReconciliation(run.version(),run.steps().getFirst().actionId(),600L,"consulta"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("author",null,List.of(new SimpleGrantedAuthority("ROLE_APPROVER"))));
        assertEquals("SELF_APPROVAL_FORBIDDEN",assertThrows(LifecycleException.class,()->recovery.decide(request.authorizationId(),new InterventionCommands.Decide(0L,"no"),true)).code());
        authenticate("APPROVER");assertEquals("REJECTED",recovery.decide(request.authorizationId(),new InterventionCommands.Decide(0L,"rechazar"),false).status());
        assertThrows(LifecycleException.class,()->recovery.decide(request.authorizationId(),new InterventionCommands.Decide(1L,"aprobar"),true));
    }
}
