package com.miec.integration.contract;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Destinatario HTTP de test, independiente de PetClinic, con ledger y efecto JDBC
 * en la misma transacción. No se distribuye en producción ni certifica un tercero.
 */
public final class HttpRecipientFixture implements AutoCloseable {
    public static final String API_KEY="recipient-fixture-test-key-2026";
    private final HttpServer server;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json=JsonMapper.builder().build();
    private final CountDownLatch committed=new CountDownLatch(1);
    private final CountDownLatch release=new CountDownLatch(1);
    private volatile String mode="NORMAL";

    /** Usa únicamente una base aislada indicada por la prueba, nunca la base de MIEC del usuario. */
    public HttpRecipientFixture(String database) throws IOException {
        var source=new DriverManagerDataSource(database,"sa",""); jdbc=new JdbcTemplate(source);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE IF NOT EXISTS recipient_operation(operation_key UUID PRIMARY KEY,destination VARCHAR(255),fingerprint VARCHAR(64),body TEXT,result_reference VARCHAR(500))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS recipient_effect(operation_key UUID PRIMARY KEY REFERENCES recipient_operation(operation_key))");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(workers); server.createContext("/",this::handle); server.start();
    }

    /** Dirección loopback del transporte real, elegida por el sistema operativo. */
    public URI base() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()); }
    /** Cuenta efectos durables, no el número de solicitudes HTTP. */
    public int effects() { return jdbc.queryForObject("SELECT COUNT(*) FROM recipient_effect",Integer.class); }
    /** Activa fallos controlados solo en el destinatario de test. */
    public void mode(String mode) { this.mode=mode; }
    /** Permite terminar la JVM de MIEC después del commit remoto y antes de entregarle la respuesta. */
    public boolean awaitCommit() throws InterruptedException { return committed.await(20,TimeUnit.SECONDS); }
    /** Libera respuestas detenidas por la prueba. */
    public void release() { release.countDown(); }

    /** Atiende el protocolo autenticado; no convierte un 404 genérico en ausencia de operación. */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if(!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-MIEC-Api-Key"))) { reply(exchange,401,Map.of("code","UNAUTHORIZED")); return; }
            if(exchange.getRequestMethod().equals("GET")) {
                if(mode.equals("REDIRECT")) { exchange.getResponseHeaders().set("Location",base()+"/redirected");exchange.sendResponseHeaders(302,-1);return; }
                if(mode.equals("GENERIC_404")) { reply(exchange,404,Map.of("code","OTHER_NOT_FOUND"));return; }
                if(mode.equals("OVERSIZED")) { raw(exchange,200,"x".repeat(70000));return; }
                if(mode.equals("SLOW_BODY")) {
                    exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,0);
                    exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); release.await(10,TimeUnit.SECONDS); return;
                }
                var key=UUID.fromString(exchange.getRequestURI().getPath().substring("/miec/operations/".length()));
                var rows=jdbc.queryForList("SELECT * FROM recipient_operation WHERE operation_key=?",key);
                if(rows.isEmpty()) reply(exchange,404,Map.of("code","OPERATION_NOT_FOUND"));
                else {
                    var row=rows.getFirst();
                    reply(exchange,200,result(key,row.get("DESTINATION").toString(),mode.equals("WRONG_IDENTITY")?"b".repeat(64):row.get("FINGERPRINT").toString(),row.get("RESULT_REFERENCE").toString()));
                }
                return;
            }
            if(!exchange.getRequestMethod().equals("POST")||!exchange.getRequestURI().getPath().equals("/miec/operations")) { reply(exchange,404,Map.of("code","OTHER_NOT_FOUND"));return; }
            UUID key=UUID.fromString(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            String destination=exchange.getRequestHeaders().getFirst("X-MIEC-Destination");
            String fingerprint=exchange.getRequestHeaders().getFirst("X-MIEC-Fingerprint");
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            var applied=apply(key,destination,fingerprint,body); committed.countDown();
            if(mode.equals("CLOSE_AFTER_COMMIT")) return;
            if(mode.equals("HOLD_AFTER_COMMIT")) release.await(20,TimeUnit.SECONDS);
            reply(exchange,201,applied);
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(RuntimeException invalid) { reply(exchange,409,Map.of("code","CONFLICT")); }
        finally { exchange.close(); }
    }

    /** Serializa la prueba de replay y confirma ledger/efecto juntos; otro contenido nunca reutiliza la clave. */
    private synchronized Map<String,Object> apply(UUID key,String destination,String fingerprint,String body) {
        return transaction.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM recipient_operation WHERE operation_key=?",key);
            if(!rows.isEmpty()) {
                var row=rows.getFirst();
                if(!destination.equals(row.get("DESTINATION"))||!fingerprint.equals(row.get("FINGERPRINT"))||!body.equals(row.get("BODY")))
                    throw new IllegalStateException("Conflicto de contenido de test");
                return result(key,destination,fingerprint,row.get("RESULT_REFERENCE").toString());
            }
            String reference="fixture-effect:"+key;
            jdbc.update("INSERT INTO recipient_operation VALUES (?,?,?,?,?)",key,destination,fingerprint,body,reference);
            jdbc.update("INSERT INTO recipient_effect VALUES (?)",key);
            return result(key,destination,fingerprint,reference);
        });
    }
    /** Resultado público que el adaptador debe comprobar antes de aceptar APPLIED. */
    private Map<String,Object> result(UUID key,String destination,String fingerprint,String reference) {
        return Map.of("status","APPLIED","operationKey",key,"destinationId",destination,"fingerprint",fingerprint,"resultReference",reference);
    }
    /** Emite JSON sin respuestas de dominio particulares como visitas o reservas. */
    private void reply(HttpExchange exchange,int status,Object body) throws IOException { raw(exchange,status,json.writeValueAsString(body)); }
    /** Envía bytes de prueba con Content-Type explícito. */
    private void raw(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    /** Termina solo recursos de esta prueba y fuerza cierre del ledger para permitir su reapertura. */
    @Override public void close() { release.countDown();server.stop(0);workers.shutdownNow();jdbc.execute("SHUTDOWN"); }
}
