package com.miec.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static com.miec.orchestration.LifecycleCommands.*;
import static org.junit.jupiter.api.Assertions.*;

/** Historial real con auditoría, límites y cursores sin omitir registros de igual fecha. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:lifecycle_history;DB_CLOSE_DELAY=-1")
class LifecycleHistoryTest {
    @Autowired private LifecycleService service;
    @Autowired private JdbcTemplate jdbc;

    /** Prepara un ciclo independiente cuyo historial de transiciones está inicialmente vacío. */
    private UUID cycle() {
        var system = service.createSystem(new CreateSystem("history", null, null, null));
        return service.createCycle(system.systemId(), new CreateCycle(null), "creator").cycleId();
    }

    /** Una consulta no cambia el ciclo y el inicio conserva actor, motivo y referencia de evento. */
    @Test void shouldReturnStartedTransitionWithoutChangingState() {
        UUID cycle = cycle();
        assertTrue(service.history(cycle, 50, null).items().isEmpty());
        var started = service.startCycle(cycle, new StartCycle(0L, "validar API"), "operator");
        var page = service.history(cycle, 50, null);
        assertEquals(1, page.items().size());
        assertNull(page.nextCursor());
        var transition = page.items().getFirst();
        assertEquals("operator", transition.actorId());
        assertEquals("validar API", transition.reason());
        assertEquals("E00_INACTIVO", transition.fromState());
        assertEquals("E01_INICIALIZANDO", transition.toState());
        assertEquals("MIEC-F01:E00->E01", transition.rule());
        assertNotNull(transition.eventId());
        assertEquals(started.startedAt(), transition.occurredAt());
        assertEquals(started, service.getCycle(cycle));
    }

    /** Fixtures históricas de igual fecha se recorren una sola vez mediante el identificador como desempate. */
    @Test void shouldPaginateTransitionsWithEqualTimestamps() {
        UUID cycle = cycle();
        var expected = new java.util.HashSet<UUID>();
        for (int i=0; i<3; i++) {
            UUID id = UUID.randomUUID(); expected.add(id);
            jdbc.update("""
                    INSERT INTO state_transition(transition_id,cycle_id,from_state,to_state,transition_status,occurred_at)
                    VALUES (?,?,'E00_INACTIVO','E01_INICIALIZANDO','COMPLETED',TIMESTAMP '2025-01-01 00:00:00')
                    """, id, cycle);
        }
        var found = new java.util.HashSet<UUID>();
        UUID after = null;
        for (int i=0; i<3; i++) {
            var page = service.history(cycle, 1, after);
            assertEquals(1, page.items().size());
            assertTrue(found.add(page.items().getFirst().transitionId()));
            assertNull(page.items().getFirst().actorId());
            assertNull(page.items().getFirst().reason());
            after = page.nextCursor();
            if (i < 2) assertNotNull(after); else assertNull(after);
        }
        assertEquals(expected, found);
    }

    /** Un cursor de otro ciclo o una página fuera de límites no se aceptan silenciosamente. */
    @Test void shouldValidateLimitCursorAndResource() {
        UUID first = cycle(), second = cycle();
        service.startCycle(first, new StartCycle(0L, "inicio"), "op");
        UUID cursor = service.history(first, 50, null).items().getFirst().transitionId();
        assertEquals("INVALID_CURSOR", assertThrows(LifecycleException.class, () -> service.history(second, 1, cursor)).code());
        assertThrows(LifecycleException.class, () -> service.history(first, 1, UUID.randomUUID()));
        assertThrows(LifecycleException.class, () -> service.history(first, 0, null));
        assertThrows(LifecycleException.class, () -> service.history(first, 101, null));
        assertEquals(LifecycleException.Kind.NOT_FOUND, assertThrows(LifecycleException.class,
                () -> service.history(UUID.randomUUID(), 50, null)).kind());
    }
}
