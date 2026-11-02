package com.miec.experiment.petclinic;

import com.miec.action.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Valida transporte y respuestas del adaptador; la durabilidad se prueba con PetClinic real en C09. */
class PetClinicVisitExecutorTest {
    private static final String API_KEY="c09-local-adapter-test-key";

    /** Construye contexto completo con clave, huella y adquisición del intento. */
    private IdempotentActionExecutor.Context context(ExecutionRequest request) {
        return new IdempotentActionExecutor.Context(new ExecutionOperationRegistry.Operation(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),request.destinationId(),request.fingerprint(),request),
                new ExecutionActivityService.Ownership(UUID.randomUUID(),"worker",1),request);
    }

    /** Serializa una respuesta válida del contrato para una operación concreta. */
    private String result(IdempotentActionExecutor.Context context) {
        return """
                {"status":"APPLIED","operationKey":"%s","fingerprint":"%s","visitId":17,
                 "resultReference":"petclinic:visit:17","recordedAt":"%s"}
                """.formatted(context.operation().key(),context.request().fingerprint(),Instant.now());
    }

    /** Contesta con JSON y cierra el intercambio de prueba. */
    private void respond(HttpExchange exchange,int status,String body) throws IOException {
        try(exchange) {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }
    }

    /** Comprueba bytes exactos, headers autenticados, NOT_FOUND contractual y respuesta aplicada. */
    @Test @DisplayName("Adaptador envía bytes capturados y valida clave, huella y resultado")
    void shouldUseExactBytesAndAuthenticatedContract() throws Exception {
        var request=PetClinicVisitExecutor.visitRequest(900001,900001,LocalDate.now().plusDays(7),"C09 adapter");
        var context=context(request);var sent=new AtomicReference<byte[]>();var applied=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/miec/",exchange->{
            assertEquals(API_KEY,exchange.getRequestHeaders().getFirst("X-MIEC-Api-Key"));
            if(exchange.getRequestMethod().equals("POST")) {
                sent.set(exchange.getRequestBody().readAllBytes());applied.set(true);
                assertEquals(request.fingerprint(),exchange.getRequestHeaders().getFirst("X-MIEC-Fingerprint"));
                respond(exchange,201,result(context));
            } else respond(exchange,applied.get()?200:404,applied.get()?result(context):"{\"code\":\"OPERATION_NOT_FOUND\",\"message\":\"absent\"}");
        });
        server.start();
        try {
            var adapter=new PetClinicVisitExecutor(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),API_KEY,request,Duration.ofSeconds(5));
            assertEquals(IdempotentActionExecutor.Status.NOT_FOUND,adapter.lookup(context).status());
            assertEquals("petclinic:visit:17",adapter.execute(context));assertArrayEquals(request.bodyBytes(),sent.get());
            assertEquals(IdempotentActionExecutor.Status.APPLIED,adapter.lookup(context).status());
        } finally { server.stop(0); }
    }

    /** Error de autenticación, 404 ajeno y huella incompatible siempre producen UNKNOWN. */
    @Test @DisplayName("Errores y respuestas ajenas no se convierten en ausencia de efectos")
    void shouldRejectUntrustedResponses() throws Exception {
        var request=PetClinicVisitExecutor.visitRequest(1,1,LocalDate.now().plusDays(1),"visit");var context=context(request);
        var status=new AtomicInteger(401);var body=new AtomicReference<>("{\"code\":\"UNAUTHORIZED\"}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/miec/",exchange->respond(exchange,status.get(),body.get()));server.start();
        try {
            var adapter=new PetClinicVisitExecutor(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),API_KEY,request,Duration.ofSeconds(5));
            assertEquals(IdempotentActionExecutor.Status.UNKNOWN,adapter.lookup(context).status());
            status.set(404);body.set("{\"code\":\"OTHER_RESOURCE_NOT_FOUND\"}");
            assertEquals(IdempotentActionExecutor.Status.UNKNOWN,adapter.lookup(context).status());
            status.set(200);body.set(result(context).replace(request.fingerprint(),"0".repeat(64)));
            assertEquals(IdempotentActionExecutor.Status.UNKNOWN,adapter.lookup(context).status());
            assertThrows(IllegalStateException.class,()->adapter.execute(context));
        } finally { server.stop(0); }
    }

    /** La espera HTTP tiene límite y no convierte una respuesta tardía en NOT_FOUND. */
    @Test @DisplayName("Timeout de transporte mantiene resultado incierto")
    void shouldTreatTimeoutAsUnknown() throws Exception {
        var request=PetClinicVisitExecutor.visitRequest(1,1,LocalDate.now().plusDays(1),"visit");var context=context(request);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var threads=Executors.newCachedThreadPool();
        server.setExecutor(threads);server.createContext("/miec/",exchange->{
            try { Thread.sleep(500);respond(exchange,200,result(context)); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();exchange.close(); }
            catch(IOException disconnected) { exchange.close(); }
        });server.start();
        try {
            var adapter=new PetClinicVisitExecutor(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),API_KEY,request,Duration.ofMillis(30));
            assertEquals(IdempotentActionExecutor.Status.UNKNOWN,adapter.lookup(context).status());
        } finally { server.stop(0);threads.shutdownNow(); }
    }

    /** Credenciales débiles, HTTP no local y otros contextos se rechazan antes de enviar. */
    @Test @DisplayName("Configuración insegura y solicitudes distintas son rechazadas")
    void shouldRejectUnsafeConfiguration() {
        var request=PetClinicVisitExecutor.visitRequest(1,1,LocalDate.now().plusDays(1),"visit");
        assertThrows(IllegalArgumentException.class,()->new PetClinicVisitExecutor(URI.create("http://example.invalid"),API_KEY,request,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new PetClinicVisitExecutor(URI.create("http://localhost"),"short",request,Duration.ofSeconds(1)));
        var other=PetClinicVisitExecutor.visitRequest(1,2,LocalDate.now().plusDays(1),"other");
        var adapter=new PetClinicVisitExecutor(URI.create("http://localhost"),API_KEY,request,Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class,()->adapter.lookup(context(other)));
    }
}
