package com.miec.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static com.miec.orchestration.PlanExecutionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reserva y propiedad reales con dos solicitantes, rollback y evolución aditiva del esquema. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:plan_coordination;DB_CLOSE_DELAY=-1")
class PlanExecutionStoreTest {
    @Autowired LifecycleService cycles;
    @Autowired InterventionWorkflow workflow;
    @Autowired PlanExecutionStore store;
    @Autowired JdbcTemplate jdbc;

    /** Captura versiones actuales antes de la reserva. */
    private Start command(InterventionViews.Plan plan) { return new Start(cycles.getCycle(plan.cycleId()).version(),plan.version(),"ejecutar"); }

    /** La reserva crea pasos ordenados y transición sin llamar al destinatario ni crear intentos. */
    @Test void shouldReserveCoordinationWithOrderedSteps() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,2,true);
        var claim=store.reserve(plan.cycleId(),plan.planId(),command(plan),"author");
        var run=store.view(plan.planId());
        assertEquals(claim.runId(),run.runId()); assertEquals("E07_EJECUTANDO",run.cycleState());
        assertEquals(2,run.steps().size()); assertEquals(0,run.steps().getFirst().ordinal());
        assertEquals(plan.actions().getFirst().actionId(),run.steps().getFirst().actionId());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM execution_attempt WHERE plan_id=?",Integer.class,plan.planId()));
        assertThrows(LifecycleException.class,()->store.reserve(plan.cycleId(),plan.planId(),command(plan),"author"));
        assertEquals("COORDINATOR_BUSY",assertThrows(LifecycleException.class,()->store.resume(plan.planId(),new Control(0L,"reanudar"),"author")).code());
    }

    /** Dos reservas concurrentes producen un solo run y un único cambio a E07. */
    @Test void shouldRejectConcurrentReservation() throws Exception {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true); var command=command(plan);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<Boolean> task=()->{gate.await();try{store.reserve(plan.cycleId(),plan.planId(),command,"author");return true;}
                catch(LifecycleException denied){assertEquals("PLAN_EXECUTION_EXISTS",denied.code());return false;}};
            var first=pool.submit(task);var second=pool.submit(task);gate.countDown();
            assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM plan_execution WHERE plan_id=?",Integer.class,plan.planId()));
    }

    /** Un fallo al insertar un paso revierte run, auditoría y transición completa. */
    @Test void shouldRollbackReservationWhenStepCannotBeStored() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);var before=cycles.getCycle(plan.cycleId());
        jdbc.execute("ALTER TABLE plan_execution_step ADD CONSTRAINT test_step_failure CHECK(action_id <> CAST('"+plan.actions().getFirst().actionId()+"' AS UUID))");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->store.reserve(plan.cycleId(),plan.planId(),command(plan),"author"));
            assertEquals(before,cycles.getCycle(plan.cycleId()));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM plan_execution WHERE plan_id=?",Integer.class,plan.planId()));
        } finally { jdbc.execute("ALTER TABLE plan_execution_step DROP CONSTRAINT test_step_failure"); }
    }

    /** Una adquisición vencida puede reemplazarse solo antes de despachar; su credencial anterior deja de valer. */
    @Test void shouldFenceExpiredCoordinatorAndRejectUncertainResume() {
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);
        var old=store.reserve(plan.cycleId(),plan.planId(),command(plan),"author");
        expire(old.runId());
        var current=store.resume(plan.planId(),new Control(0L,"recuperar reserva sin despacho"),"author");
        assertEquals(2L,current.generation());
        assertEquals("COORDINATOR_LOST",assertThrows(LifecycleException.class,()->store.beginNext(old)).code());
        store.beginNext(current); expire(current.runId());
        assertEquals("DISPATCH_UNCERTAIN",assertThrows(LifecycleException.class,()->store.resume(plan.planId(),new Control(store.view(plan.planId()).version(),"no reenviar"),"author")).code());
        var recovered=store.recover(plan.planId(),new Control(store.view(plan.planId()).version(),"inspección"),"approver");
        assertEquals("RECOVERY_REQUIRED",recovered.state()); assertEquals("E13_RECUPERANDO",recovered.cycleState());
    }

    /** El contenido histórico descriptivo y los permisos vencidos no permiten reservar ejecución. */
    @Test void shouldRejectLegacyPlanAndExpiredAuthorizationWithoutChangingCycle() {
        var legacy=PlanExecutionTestSupport.approved(cycles,workflow,1,false);
        assertEquals("EXECUTABLE_BINDING_INVALID",assertThrows(LifecycleException.class,()->store.reserve(legacy.cycleId(),legacy.planId(),command(legacy),"author")).code());
        var plan=PlanExecutionTestSupport.approved(cycles,workflow,1,true);var before=cycles.getCycle(plan.cycleId());
        jdbc.update("UPDATE authorizations SET expires_at=? WHERE plan_id=?",Timestamp.from(Instant.now().minusSeconds(1)),plan.planId());
        assertEquals("EXECUTION_AUTHORIZATION_INVALID",assertThrows(LifecycleException.class,()->store.reserve(plan.cycleId(),plan.planId(),command(plan),"author")).code());
        assertEquals(before,cycles.getCycle(plan.cycleId()));
    }

    /** V013 no transforma planes previos en ejecuciones ni modifica sus datos. */
    @Test void shouldUpgradeV012WithoutInventingCoordination() {
        var source=new SingleConnectionDataSource("jdbc:h2:mem:coord_upgrade_"+UUID.randomUUID(),"sa","",true);
        try {
            Flyway.configure().dataSource(source).target("012").load().migrate();
            var sql=new JdbcTemplate(source);UUID system=UUID.randomUUID(),cycle=UUID.randomUUID(),plan=UUID.randomUUID();
            sql.update("INSERT INTO system_objective(system_id,name) VALUES (?,'legacy')",system);
            sql.update("INSERT INTO cycle(cycle_id,system_id) VALUES (?,?)",cycle,system);
            sql.update("INSERT INTO intervention_plan(plan_id,cycle_id,status) VALUES (?,?,'APPROVED')",plan,cycle);
            var before=sql.queryForMap("SELECT * FROM intervention_plan WHERE plan_id=?",plan);
            assertEquals(1,Flyway.configure().dataSource(source).target("013").load().migrate().migrationsExecuted);
            assertEquals(before,sql.queryForMap("SELECT * FROM intervention_plan WHERE plan_id=?",plan));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM plan_execution",Integer.class));
        } finally { source.destroy(); }
    }

    /** Simula únicamente vencimiento de propiedad en una base de test, sin dormir ni alterar el reloj global. */
    private void expire(UUID run) { jdbc.update("UPDATE plan_execution SET lease_expires_at=? WHERE run_id=?",Timestamp.from(Instant.now().minusSeconds(1)),run); }
}
