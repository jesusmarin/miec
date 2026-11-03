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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.miec.orchestration.PlanExecutionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Finalización real con F01, bloqueo compartido con IR-009 y auditoría atómica. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:execution_finish;DB_CLOSE_DELAY=-1")
class PlanExecutionFinalizationTest {
    @Autowired LifecycleService cycles;
    @Autowired InterventionWorkflow workflow;
    @Autowired PlanExecutionApplicationService service;
    @Autowired ExecutionAttemptService attempts;
    @Autowired ExecutionEvidenceService evidence;
    @Autowired ExecutionTestConfiguration.TestAdapter adapter;
    @Autowired JdbcTemplate jdbc;

    /** Aísla identidad y hooks en cada escenario. */
    @BeforeEach void prepare() { authenticate("AUTHOR"); adapter.afterEffect=()->{}; }
    /** Libera contexto de seguridad y cualquier fallo remoto simulado. */
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); adapter.afterEffect=()->{}; }
    /** Instala exclusivamente identidades de servidor en el hilo de prueba. */
    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(role.equals("AUTHOR")?"author":"approver",null,
                List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    /** Ejecuta un plan real aprobado y retorna su coordinación. */
    private Run execute() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);
        return service.start(plan.cycleId(),plan.planId(),new Start(cycles.getCycle(plan.cycleId()).version(),plan.version(),"ejecutar"));
    }
    /** Captura las dos versiones de la revisión humana. */
    private Finish command(Run run) { return new Finish(run.version(),cycles.getCycle(run.cycleId()).version(),"efectos revisados"); }

    /** E08 conserva evidencia e intentos y no vuelve a ejecutar aunque el permiso anterior venza. */
    @Test void shouldFinalizeWithAtomicAuditAndNoNewEffects() {
        var run=execute();var command=command(run);int effects=adapter.effects.get();
        jdbc.update("UPDATE authorizations SET expires_at=? WHERE plan_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),run.planId());
        authenticate("APPROVER");var result=service.finish(run.planId(),command);
        assertEquals("E08_VERIFICANDO",result.cycleState());assertEquals("APPLIED",result.state());assertFalse(result.requiresReview());
        assertEquals(command.expectedCycleVersion()+1,cycles.getCycle(run.cycleId()).version());
        assertEquals(run.version()+1,result.version());assertEquals("approver",result.updatedBy());assertEquals(effects,adapter.effects.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=? AND event_type='PLAN_EXECUTION_FINALIZED'",Integer.class,run.cycleId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM state_transition WHERE cycle_id=? AND to_state='E08_VERIFICANDO'",Integer.class,run.cycleId()));
        assertThrows(LifecycleException.class,()->service.finish(run.planId(),command));
    }

    /** UNKNOWN y contradicciones no autorizan finalizar ni cambian el ciclo. */
    @Test void shouldRejectUncertaintyAndContradictions() {
        adapter.afterEffect=()->{throw new IllegalStateException("respuesta perdida");};
        var uncertain=execute();authenticate("APPROVER");
        assertEquals("RESULT_UNCONFIRMED",assertThrows(LifecycleException.class,()->service.finish(uncertain.planId(),command(uncertain))).code());
        assertEquals("E13_RECUPERANDO",cycles.getCycle(uncertain.cycleId()).state());
        adapter.afterEffect=()->{};authenticate("AUTHOR");var applied=execute();
        var attempt=attempts.findHistory(applied.planId(),applied.steps().getFirst().actionId()).getFirst();
        evidence.report(UUID.randomUUID(),attempt.ownership(),ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED,"contradicción",Instant.now().minusSeconds(1));
        authenticate("APPROVER");assertThrows(LifecycleException.class,()->service.finish(applied.planId(),command(applied)));
        assertEquals("E07_EJECUTANDO",cycles.getCycle(applied.cycleId()).state());
    }

    /** Rol y ambas versiones se verifican antes de cualquier transición. */
    @Test void shouldRejectRoleAndStaleVersions() {
        var run=execute();var current=command(run);
        assertThrows(LifecycleException.class,()->service.finish(run.planId(),current));authenticate("APPROVER");
        assertEquals("RUN_VERSION_CONFLICT",assertThrows(LifecycleException.class,()->service.finish(run.planId(),new Finish(0L,current.expectedCycleVersion(),"viejo"))).code());
        assertEquals("VERSION_CONFLICT",assertThrows(LifecycleException.class,()->service.finish(run.planId(),new Finish(run.version(),0L,"viejo"))).code());
    }

    /** Un fallo real al insertar el evento revierte ciclo, pasos y versión del run. */
    @Test void shouldRollbackWhenFinalizationAuditFails() {
        var run=execute();authenticate("APPROVER");
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT test_finish_audit CHECK(cycle_id<>CAST('"+run.cycleId()+"' AS UUID) OR event_type<>'PLAN_EXECUTION_FINALIZED')");
        try {
            assertThrows(RuntimeException.class,()->service.finish(run.planId(),command(run)));
            assertEquals(run.version(),service.get(run.planId()).version());assertEquals("E07_EJECUTANDO",cycles.getCycle(run.cycleId()).state());
        } finally { jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT test_finish_audit"); }
    }

    /** Dos revisiones concurrentes producen una sola transición y un rechazo por versión. */
    @Test void shouldFinalizeOnlyOnceUnderConcurrency() throws Exception {
        var run=execute();var command=command(run);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> action=()->{authenticate("APPROVER");try {start.await();service.finish(run.planId(),command);return true;}
                catch(LifecycleException rejected){assertEquals("RUN_VERSION_CONFLICT",rejected.code());return false;}
                finally {SecurityContextHolder.clearContext();}};
            var first=pool.submit(action);var second=pool.submit(action);start.countDown();
            assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        assertEquals("E08_VERIFICANDO",cycles.getCycle(run.cycleId()).state());
    }
}
