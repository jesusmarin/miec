package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Valida identidad estable y vinculación inmutable antes de invocar un destinatario. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:operation_test;DB_CLOSE_DELAY=-1")
class ExecutionOperationRegistryTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionOperationRegistry registry;

    /** Una acción conserva clave; cambios de contenido, destino o permiso se rechazan. */
    @Test @DisplayName("Clave estable vinculada a solicitud, destinatario y autorización")
    void shouldBindKeyToImmutableRequest() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var operation=registry.prepare(f.command(),"destination","a".repeat(64));
        assertEquals(operation,registry.prepare(f.command(),"destination","a".repeat(64)));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->registry.prepare(f.command(),"other","a".repeat(64)));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->registry.prepare(f.command(),"destination","b".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->registry.prepare(f.command(),"destination","invalid"));
        jdbc.update("UPDATE authorizations SET revoked_at=CURRENT_TIMESTAMP WHERE authorization_id=?",f.executeAuthorization());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->registry.prepare(f.command(),"destination","a".repeat(64)));
    }

    /** Dos reservas concurrentes devuelven la misma identidad y una sola fila. */
    @Test @DisplayName("Reservas concurrentes comparten una sola clave")
    void shouldReserveOnlyOneKey() throws Exception {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<ExecutionOperationRegistry.Operation> task=()->{barrier.await(5,TimeUnit.SECONDS);return registry.prepare(f.command(),"destination","a".repeat(64));};
            var a=pool.submit(task);var b=pool.submit(task);
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM execution_operation WHERE plan_id=?",Integer.class,f.plan()));
    }
}
