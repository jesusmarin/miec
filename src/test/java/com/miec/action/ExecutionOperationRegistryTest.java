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
        var operation=registry.prepare(f.command(),new ExecutionRequest("destination","application/json","{\"value\":1}"));
        assertEquals(operation,registry.prepare(f.command(),new ExecutionRequest("destination","application/json","{\"value\":1}")));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->registry.prepare(f.command(),new ExecutionRequest("other","application/json","{\"value\":1}")));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->registry.prepare(f.command(),new ExecutionRequest("destination","application/json","{\"value\":2}")));
        assertThrows(NullPointerException.class,()->registry.prepare(f.command(),new ExecutionRequest("destination","application/json",null)));
        jdbc.update("UPDATE authorizations SET revoked_at=CURRENT_TIMESTAMP WHERE authorization_id=?",f.executeAuthorization());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->registry.prepare(f.command(),new ExecutionRequest("destination","application/json","{\"value\":1}")));
    }

    /** No fabrica un snapshot para claves históricas: permite consulta y rechaza ejecución. */
    @Test @DisplayName("Una operación histórica sin cuerpo puede consultarse pero no reejecutarse")
    void shouldKeepLegacyRequestUnknown() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        var request=new ExecutionRequest("destination","application/json","{}");
        registry.prepare(f.command(),request);
        jdbc.update("UPDATE execution_operation SET request_format=NULL,request_media_type=NULL,request_body=NULL WHERE plan_id=?",f.plan());
        assertNull(registry.findAuthorized(f.command()).orElseThrow().request());
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->registry.prepare(f.command(),request));
        assertNull(jdbc.queryForObject("SELECT request_body FROM execution_operation WHERE plan_id=?",String.class,f.plan()));
    }

    /** Dos reservas concurrentes devuelven la misma identidad y una sola fila. */
    @Test @DisplayName("Reservas concurrentes comparten una sola clave")
    void shouldReserveOnlyOneKey() throws Exception {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<ExecutionOperationRegistry.Operation> task=()->{barrier.await(5,TimeUnit.SECONDS);return registry.prepare(f.command(),new ExecutionRequest("destination","application/json","{\"value\":1}"));};
            var a=pool.submit(task);var b=pool.submit(task);
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM execution_operation WHERE plan_id=?",Integer.class,f.plan()));
    }
}
