package com.miec.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Comprueba rutas reales, códigos HTTP y validación sin mocks de controladores. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.datasource.url=jdbc:h2:mem:lifecycle_api;DB_CLOSE_DELAY=-1", "server.address=127.0.0.1",
                "miec.api.author.username=api-author","miec.api.author.password=api-author-test-password",
                "miec.api.approver.username=api-approver","miec.api.approver.password=api-approver-test-password"})
class LifecycleApiIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString(
            "api-author:api-author-test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private final JsonMapper json = JsonMapper.builder().build();

    /** Publica un comando JSON; autentica al autor y permite falsificar una cabecera que el servidor debe ignorar. */
    private HttpResponse<String> post(String path, String body, String actor) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("Authorization", credentials);
        if (actor != null) request.header("X-MIEC-Actor", actor);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Consulta el servidor real sobre un puerto asignado a esta prueba. */
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("Authorization", credentials).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Construye direcciones locales sin utilizar el puerto de la aplicación del usuario. */
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    /** Registra un sistema independiente para cada escenario HTTP. */
    private String createSystem() throws Exception {
        var response = post("/api/systems", "{\"name\":\"Sistema API\"}", null);
        assertEquals(201, response.statusCode());
        return json.readTree(response.body()).path("systemId").asString();
    }

    /** POST y GET representan el mismo recurso persistido y anuncian su Location. */
    @Test void shouldCreateAndReadSystemOverHttp() throws Exception {
        var response = post("/api/systems", "{\"name\":\" sistema \",\"domain\":\"software\"}", null);
        assertEquals(201, response.statusCode());
        String id = json.readTree(response.body()).path("systemId").asString();
        assertEquals("/api/systems/" + id, response.headers().firstValue("Location").orElseThrow());
        var found = get("/api/systems/" + id);
        assertEquals(200, found.statusCode());
        assertEquals(json.readTree(response.body()), json.readTree(found.body()));
        assertEquals("sistema", json.readTree(found.body()).path("name").asString());
    }

    /** El ciclo HTTP nace A0 inactivo y expone su versión, no un estado solicitado por el cliente. */
    @Test void shouldCreateAndReadInactiveCycle() throws Exception {
        String system = createSystem();
        var response = post("/api/systems/" + system + "/cycles", "{}", "operator");
        assertEquals(201, response.statusCode());
        JsonNode created = json.readTree(response.body());
        String id = created.path("cycleId").asString();
        assertEquals("/api/cycles/" + id, response.headers().firstValue("Location").orElseThrow());
        assertEquals("E00_INACTIVO", created.path("state").asString());
        assertEquals("A0", created.path("autonomyLevel").asString());
        assertEquals(0, created.path("version").asLong());
        assertEquals(created, json.readTree(get("/api/cycles/" + id).body()));
    }

    /** Errores de campos, UUID y JSON tienen respuesta 400 útil y sin trazas del servidor. */
    @Test void shouldRejectInvalidRequestsAndUnknownFields() throws Exception {
        assertEquals(400, get("/api/systems/not-a-uuid").statusCode());
        assertEquals(400, post("/api/systems", "{", null).statusCode());
        var invalid = post("/api/systems", "{\"name\":\" \"}", null);
        assertEquals(400, invalid.statusCode());
        assertTrue(json.readTree(invalid.body()).path("fields").has("name"));
        assertEquals(400, post("/api/systems", "{\"name\":\"s\",\"status\":\"INACTIVE\"}", null).statusCode());
        String system = createSystem();
        assertEquals(400, post("/api/systems/" + system + "/cycles", "{\"state\":\"E07_EJECUTANDO\"}", "op").statusCode());
        assertFalse(invalid.body().contains("stackTrace"));
    }

    /** Los recursos inexistentes mantienen 404; omitir o falsificar X-MIEC-Actor no cambia la identidad real. */
    @Test void shouldRejectMissingResourcesAndIgnoreActorHeader() throws Exception {
        assertEquals(404, get("/api/systems/" + UUID.randomUUID()).statusCode());
        assertEquals(404, get("/api/cycles/" + UUID.randomUUID()).statusCode());
        assertEquals(404, post("/api/systems/" + UUID.randomUUID() + "/cycles", "{}", "op").statusCode());
        String system = createSystem();
        assertEquals(201, post("/api/systems/" + system + "/cycles", "{}", null).statusCode());
        assertEquals(201, post("/api/systems/" + system + "/cycles", "{}", " ").statusCode());
    }

    /** La ruta de inicio requiere versión/motivo y rechaza tanto duplicados como cambios de estado arbitrarios. */
    @Test void shouldStartOverHttpWithVersionAndRejectDuplicate() throws Exception {
        String system = createSystem();
        var created = post("/api/systems/" + system + "/cycles", "{}", "creator");
        String cycle = json.readTree(created.body()).path("cycleId").asString();
        String path = "/api/cycles/" + cycle + "/start";
        assertEquals(400, post(path, "{\"reason\":\"inicio\"}", "op").statusCode());
        assertEquals(400, post(path, "{\"expectedVersion\":0.5,\"reason\":\"inicio\"}", "op").statusCode());
        assertEquals(400, post(path, "{\"expectedVersion\":0,\"reason\":\"inicio\",\"state\":\"E07_EJECUTANDO\"}", "op").statusCode());
        var started = post(path, "{\"expectedVersion\":0,\"reason\":\"inicio\"}", "op");
        assertEquals(200, started.statusCode());
        assertEquals("E01_INICIALIZANDO", json.readTree(started.body()).path("state").asString());
        assertEquals(1, json.readTree(started.body()).path("version").asLong());
        assertFalse(json.readTree(started.body()).path("startedAt").isNull());
        var duplicate = post(path, "{\"expectedVersion\":0,\"reason\":\"inicio\"}", "op");
        assertEquals(409, duplicate.statusCode());
        assertEquals("VERSION_CONFLICT", json.readTree(duplicate.body()).path("code").asString());
    }

    /** HTTP permite recorrer el historial, preserva auditoría y rechaza parámetros o cursores ajenos. */
    @Test void shouldReadHistoryAndRejectInvalidCursorsOverHttp() throws Exception {
        String system = createSystem();
        var created = post("/api/systems/" + system + "/cycles", "{}", "creator");
        String cycle = json.readTree(created.body()).path("cycleId").asString();
        String path = "/api/cycles/" + cycle + "/history";
        assertEquals(0, json.readTree(get(path).body()).path("items").size());
        assertEquals(200, post("/api/cycles/" + cycle + "/start", "{\"expectedVersion\":0,\"reason\":\"motivo HTTP\"}", "starter").statusCode());
        var response = get(path + "?limit=1");
        assertEquals(200, response.statusCode());
        var item = json.readTree(response.body()).path("items").get(0);
        assertEquals("api-author", item.path("actorId").asString());
        assertEquals("motivo HTTP", item.path("reason").asString());
        assertEquals(400, get(path + "?limit=0").statusCode());
        assertEquals(400, get(path + "?limit=101").statusCode());
        assertEquals(400, get(path + "?after=not-a-uuid").statusCode());
        var another = post("/api/systems/" + system + "/cycles", "{}", "creator");
        String otherCycle = json.readTree(another.body()).path("cycleId").asString();
        assertEquals(400, get("/api/cycles/" + otherCycle + "/history?after=" + item.path("transitionId").asString()).statusCode());
        assertEquals(404, get("/api/cycles/" + UUID.randomUUID() + "/history").statusCode());
    }

    /** Dos inicios HTTP simultáneos tienen un único ganador y una sola transición observable. */
    @Test void shouldRejectConcurrentStartOverHttp() throws Exception {
        String system = createSystem();
        var created = post("/api/systems/" + system + "/cycles", "{}", "creator");
        String cycle = json.readTree(created.body()).path("cycleId").asString();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<Integer> start = () -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return post("/api/cycles/" + cycle + "/start", "{\"expectedVersion\":0,\"reason\":\"concurrente\"}", "op").statusCode();
            };
            var first = workers.submit(start); var second = workers.submit(start);
            assertEquals(java.util.Set.of(200,409), java.util.Set.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)));
        }
        assertEquals(1, json.readTree(get("/api/cycles/" + cycle + "/history").body()).path("items").size());
    }

    /** Un fallo SQL real produce 500 saneado y no deja estado, evento ni historial parcial. */
    @Test void shouldRollbackOverHttpWithoutExposingSqlFailure() throws Exception {
        String system = createSystem();
        var created = post("/api/systems/" + system + "/cycles", "{}", "creator");
        String cycle = json.readTree(created.body()).path("cycleId").asString();
        jdbc.execute("ALTER TABLE state_transition ADD CONSTRAINT mvp_http_failure CHECK(cycle_id <> CAST('" + cycle + "' AS UUID))");
        try {
            var failed = post("/api/cycles/" + cycle + "/start", "{\"expectedVersion\":0,\"reason\":\"fallará\"}", "op");
            assertEquals(500, failed.statusCode());
            assertEquals("INTERNAL_ERROR", json.readTree(failed.body()).path("code").asString());
            assertFalse(failed.body().contains("state_transition"));
            assertFalse(failed.body().contains("SQLException"));
            assertFalse(failed.body().contains("stackTrace"));
        } finally { jdbc.execute("ALTER TABLE state_transition DROP CONSTRAINT mvp_http_failure"); }
        var after = json.readTree(get("/api/cycles/" + cycle).body());
        assertEquals("E00_INACTIVO", after.path("state").asString());
        assertEquals(0, after.path("version").asLong());
        assertTrue(after.path("startedAt").isNull());
        assertEquals(0, json.readTree(get("/api/cycles/" + cycle + "/history").body()).path("items").size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cycle_event WHERE cycle_id=?", Integer.class, UUID.fromString(cycle)));
    }
}
