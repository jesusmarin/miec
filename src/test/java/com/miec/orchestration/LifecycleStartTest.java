package com.miec.orchestration;

import com.miec.domain.Cycle;
import com.miec.state.CycleState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static com.miec.orchestration.LifecycleCommands.*;
import static org.junit.jupiter.api.Assertions.*;

/** Inicio transaccional con reglas F01, versión y trazabilidad, usando una base aislada. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:lifecycle_start;DB_CLOSE_DELAY=-1")
class LifecycleStartTest {
    @Autowired private LifecycleService service;
    @Autowired private JdbcTemplate jdbc;

    /** Crea un ciclo inactivo independiente con su evento inicial. */
    private LifecycleViews.CycleView cycle() {
        var system = service.createSystem(new CreateSystem("inicio", null, null, null));
        return service.createCycle(system.systemId(), new CreateCycle(null), "creator");
    }

    /** El estado y su transición tienen la misma fecha y el actor/motivo quedan persistidos. */
    @Test void shouldStartOnceWithAuditAndVersion() {
        var before = cycle();
        var started = service.startCycle(before.cycleId(), new StartCycle(0L, " Iniciar observación "), " operator ");
        assertEquals("E01_INICIALIZANDO", started.state());
        assertEquals(1, started.version());
        assertNotNull(started.startedAt());
        var audit = jdbc.queryForMap("""
                SELECT t.occurred_at,e.actor_id,e.reason FROM state_transition t
                JOIN cycle_event e ON e.event_id=t.trigger_event_id AND e.cycle_id=t.cycle_id WHERE t.cycle_id=?
                """, before.cycleId());
        assertEquals(started.startedAt(), ((java.sql.Timestamp) audit.get("OCCURRED_AT")).toInstant());
        assertEquals("operator", audit.get("ACTOR_ID"));
        assertEquals("Iniciar observación", audit.get("REASON"));
        assertEquals("VERSION_CONFLICT", assertThrows(LifecycleException.class,
                () -> service.startCycle(before.cycleId(), new StartCycle(0L, "otra vez"), "operator")).code());
        assertEquals("TRANSITION_NOT_ALLOWED", assertThrows(LifecycleException.class,
                () -> service.startCycle(before.cycleId(), new StartCycle(1L, "otra vez"), "operator")).code());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM state_transition WHERE cycle_id=?", Integer.class, before.cycleId()));
    }

    /** La regla compartida solo permite iniciar E00; una solicitud no puede iniciar un ciclo terminado. */
    @Test void shouldUseDomainRuleAndRejectTerminalState() {
        for (var state : CycleState.values()) {
            assertEquals(state == CycleState.E00_INACTIVO, Cycle.isValidTransition(state, CycleState.E01_INICIALIZANDO));
        }
        var before = cycle();
        jdbc.update("UPDATE cycle SET state='E14_FINALIZADO' WHERE cycle_id=?", before.cycleId());
        assertThrows(LifecycleException.class, () -> service.startCycle(before.cycleId(), new StartCycle(0L, "iniciar"), "op"));
        assertEquals("E14_FINALIZADO", service.getCycle(before.cycleId()).state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM state_transition WHERE cycle_id=?", Integer.class, before.cycleId()));
    }

    /** Dos comandos con la misma versión producen un inicio y un conflicto, sin duplicar eventos. */
    @Test void shouldSerializeConcurrentStarts() throws Exception {
        var before = cycle();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<String> start = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { service.startCycle(before.cycleId(), new StartCycle(0L, "inicio concurrente"), "op"); return "STARTED"; }
                catch (LifecycleException rejected) { return rejected.code(); }
            };
            var first = pool.submit(start); var second = pool.submit(start);
            assertEquals(java.util.Set.of("STARTED", "VERSION_CONFLICT"),
                    java.util.Set.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(1, service.getCycle(before.cycleId()).version());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM state_transition WHERE cycle_id=?", Integer.class, before.cycleId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=?", Integer.class, before.cycleId()));
    }

    /** Si falla el historial después de actualizar estado y evento, todo el inicio se revierte. */
    @Test void shouldRollbackStateAndEventWhenTransitionCannotBeWritten() {
        var before = cycle();
        jdbc.execute("ALTER TABLE state_transition ADD CONSTRAINT mvp_start_failure CHECK(cycle_id <> CAST('" + before.cycleId() + "' AS UUID))");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.startCycle(before.cycleId(), new StartCycle(0L, "fallará"), "op"));
        } finally { jdbc.execute("ALTER TABLE state_transition DROP CONSTRAINT mvp_start_failure"); }
        assertEquals(before, service.getCycle(before.cycleId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=?", Integer.class, before.cycleId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM state_transition WHERE cycle_id=?", Integer.class, before.cycleId()));
    }

    /** Versión ausente, motivo vacío, sistema inactivo e inexistencia no dejan un inicio parcial. */
    @Test void shouldRejectInvalidStartContext() {
        var before = cycle();
        assertThrows(LifecycleException.class, () -> service.startCycle(before.cycleId(), new StartCycle(null, "motivo"), "op"));
        assertThrows(LifecycleException.class, () -> service.startCycle(before.cycleId(), new StartCycle(0L, " "), "op"));
        assertThrows(LifecycleException.class, () -> service.startCycle(UUID.randomUUID(), new StartCycle(0L, "motivo"), "op"));
        jdbc.update("UPDATE system_objective SET status='INACTIVE' WHERE system_id=?", before.systemId());
        assertEquals("SYSTEM_NOT_ACTIVE", assertThrows(LifecycleException.class,
                () -> service.startCycle(before.cycleId(), new StartCycle(0L, "motivo"), "op")).code());
        assertEquals(before, service.getCycle(before.cycleId()));
    }
}
