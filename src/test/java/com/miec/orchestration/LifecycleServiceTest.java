package com.miec.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static com.miec.orchestration.LifecycleCommands.*;
import static org.junit.jupiter.api.Assertions.*;

/** Persistencia real del MVP sobre una base aislada; incluye concurrencia y rollback de auditoría. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:lifecycle_service;DB_CLOSE_DELAY=-1")
class LifecycleServiceTest {
    @Autowired private LifecycleService service;
    @Autowired private JdbcTemplate jdbc;

    /** Registra y vuelve a leer el sistema y sus valores normalizados. */
    @Test void shouldCreateAndReadSystem() {
        var created = service.createSystem(new CreateSystem(" sistema ", " software ", " descripción ", " operador "));
        assertEquals(created, service.getSystem(created.systemId()));
        assertEquals("sistema", created.name());
        assertEquals("software", created.domain());
        assertEquals("ACTIVE", created.status());
        assertNotNull(created.createdAt());
    }

    /** El ciclo nace inactivo, sin fechas ficticias de inicio y con un evento atribuible. */
    @Test void shouldCreateInactiveCyclesWithAuditAndSequence() {
        var system = service.createSystem(new CreateSystem("s", null, null, null));
        var first = service.createCycle(system.systemId(), new CreateCycle("v1"), " operator ");
        var second = service.createCycle(system.systemId(), new CreateCycle(null), "operator");
        assertEquals(first, service.getCycle(first.cycleId()));
        assertEquals(1, first.cycleNumber());
        assertEquals(2, second.cycleNumber());
        assertEquals("E00_INACTIVO", first.state());
        assertEquals("A0", first.autonomyLevel());
        assertEquals(0, first.version());
        assertNull(first.startedAt());
        assertEquals("operator", jdbc.queryForObject("SELECT actor_id FROM cycle_event WHERE cycle_id=?", String.class, first.cycleId()));
    }

    /** Los comandos no crean recursos si el padre no existe o está inactivo. */
    @Test void shouldRejectMissingAndInactiveResources() {
        assertEquals(LifecycleException.Kind.NOT_FOUND, assertThrows(LifecycleException.class, () -> service.getSystem(UUID.randomUUID())).kind());
        assertThrows(LifecycleException.class, () -> service.getCycle(UUID.randomUUID()));
        assertThrows(LifecycleException.class, () -> service.createCycle(UUID.randomUUID(), new CreateCycle(null), "op"));
        var system = service.createSystem(new CreateSystem("s", null, null, null));
        jdbc.update("UPDATE system_objective SET status='INACTIVE' WHERE system_id=?", system.systemId());
        assertEquals(LifecycleException.Kind.CONFLICT, assertThrows(LifecycleException.class,
                () -> service.createCycle(system.systemId(), new CreateCycle(null), "op")).kind());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cycle WHERE system_id=?", Integer.class, system.systemId()));
    }

    /** Los servicios internos no pueden omitir la validación que aplica REST. */
    @Test void shouldValidateCommandsAndActorBeforeWrites() {
        assertThrows(LifecycleException.class, () -> service.createSystem(new CreateSystem(" ", null, null, null)));
        var system = service.createSystem(new CreateSystem("s", null, null, null));
        assertThrows(LifecycleException.class, () -> service.createCycle(system.systemId(), new CreateCycle(null), " "));
        assertThrows(LifecycleException.class, () -> service.createCycle(system.systemId(), new CreateCycle("x".repeat(101)), "op"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cycle WHERE system_id=?", Integer.class, system.systemId()));
    }

    /** Cuatro creaciones concurrentes no comparten número ordinal dentro del sistema. */
    @Test void shouldSerializeConcurrentCycleNumbers() throws Exception {
        var system = service.createSystem(new CreateSystem("concurrent", null, null, null));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var barrier = new CyclicBarrier(4);
            Callable<Integer> create = () -> { barrier.await(5, TimeUnit.SECONDS); return service.createCycle(system.systemId(), new CreateCycle(null), "op").cycleNumber(); };
            var results = new java.util.ArrayList<Future<Integer>>();
            for (int i=0; i<4; i++) results.add(pool.submit(create));
            var numbers = new java.util.HashSet<Integer>();
            for (var result : results) numbers.add(result.get(15, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(1,2,3,4), numbers);
        }
    }

    /** Un fallo real al insertar el evento revierte el ciclo y no consume su número ordinal. */
    @Test void shouldRollbackCycleIfAuditCannotBeWritten() {
        var system = service.createSystem(new CreateSystem("rollback", null, null, null));
        String actor = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT mvp_creation_failure CHECK(actor_id <> '" + actor + "')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.createCycle(system.systemId(), new CreateCycle(null), actor));
        } finally { jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT mvp_creation_failure"); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cycle WHERE system_id=?", Integer.class, system.systemId()));
        assertEquals(1, service.createCycle(system.systemId(), new CreateCycle(null), "op").cycleNumber());
    }
}
