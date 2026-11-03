package com.miec.orchestration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.PostMeasurementTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP real de las rutas nuevas con Basic, roles e identidad no falsificable por el cliente. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:post_measure_api;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "miec.api.author.username=author","miec.api.author.password=post-author-api-test",
        "miec.api.approver.username=approver","miec.api.approver.password=post-approver-api-test"})
@DisplayName("MVP-05.6 — API autenticada de medición posterior")
class PostMeasurementApiTest {
    @LocalServerPort int port;
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** Limpia únicamente la identidad utilizada para preparar el fixture. */
    @AfterEach void cleanup() {SecurityContextHolder.clearContext();}
    /** Envía Basic y una cabecera falsa que nunca debe convertirse en el actor persistido. */
    private HttpResponse<String> send(String method,String path,Object body,String user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("X-MIEC-Actor","forged");
        if(user!=null) request.header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+(user.equals("author")?"post-author-api-test":"post-approver-api-test")).getBytes(StandardCharsets.UTF_8)));
        if(method.equals("GET"))request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    /** Ruta delimitada por los IDs reales del ciclo y plan. */
    private String path(Fixture f) {return "/api/cycles/"+f.cycleId()+"/plans/"+f.planId()+"/measurements";}

    /** Registra, revisa y cierra preservando la identidad Basic y las limitaciones de la evidencia. */
    @Test @DisplayName("Recorrido HTTP de resultado, revisión y conjunto en E08")
    void shouldRecordReviewAndSealThroughHttp() throws Exception {
        var f=ready(cycles,measures,intervention,execution);var route=path(f);
        var response=send("POST",route,command(f,cycles.getCycle(f.cycleId()).version()),"author");assertEquals(200,response.statusCode(),response.body());
        var context=json.readTree(response.body());var post=context.path("measurements").get(0);
        assertEquals("author",post.path("recordedBy").asString());assertEquals("UNVERIFIED",post.path("qualityStatus").asString());
        var review=new ReviewMeasure(context.path("cycleVersion").asLong(),f.runId(),UUID.fromString(post.path("evidenceId").asString()),ReviewVerdict.ACCEPTED_WITH_LIMITATIONS,
                post.path("method").asString(),"acta","Verificación humana limitada","revisión");
        assertEquals(403,send("POST",route+"/reviews",review,"author").statusCode());response=send("POST",route+"/reviews",review,"approver");assertEquals(200,response.statusCode(),response.body());
        context=json.readTree(response.body());assertEquals("approver",context.path("reviews").get(0).path("reviewedBy").asString());
        var seal=new SealPost(context.path("cycleVersion").asLong(),f.runId(),f.baselineAt().minusSeconds(1),f.baselineAt().plusSeconds(1),
                java.time.Instant.parse(post.path("measuredAt").asString()),now(),post.path("method").asString(),List.of(new PairSelection(f.indicatorId(),f.baselineId(),UUID.fromString(post.path("measurementId").asString()))),"conjunto");
        assertEquals(403,send("POST",route+"/seal",seal,"author").statusCode());response=send("POST",route+"/seal",seal,"approver");assertEquals(200,response.statusCode(),response.body());
        context=json.readTree(response.body());assertEquals("EXPLICIT_PAIR_V1",context.path("set").path("policyVersion").asString());assertEquals("E08_VERIFICANDO",cycles.getCycle(f.cycleId()).state());
        assertEquals(context,json.readTree(send("GET",route,null,"author").body()));
    }

    /** Rechaza falta de identidad, rol, campos no reconocidos y versiones obsoletas sin reflejar el payload. */
    @Test @DisplayName("Basic, roles, versión y contrato de entrada estricto")
    void shouldRejectUnauthenticatedForbiddenForgedAndStaleCommands() throws Exception {
        var f=ready(cycles,measures,intervention,execution);var route=path(f);var command=command(f,cycles.getCycle(f.cycleId()).version());
        assertEquals(401,send("GET",route,null,null).statusCode());assertEquals(401,send("POST",route,command,null).statusCode());
        assertEquals(403,send("POST",route,command,"approver").statusCode());
        var forged=new LinkedHashMap<String,Object>();forged.put("runId",command.runId());forged.put("measurement",command.measurement());forged.put("method",command.method());forged.put("qualityStatus","VERIFIED");
        var invalid=send("POST",route,forged,"author");assertEquals(400,invalid.statusCode());assertFalse(invalid.body().contains("VERIFIED"));
        assertEquals(200,send("POST",route,command,"author").statusCode());assertEquals(409,send("POST",route,command,"author").statusCode());
        SecurityContextHolder.clearContext();assertThrows(LifecycleException.class,()->execution.get(f.planId()));
    }

    /** Swagger publica seguridad y casos de rechazo para las cuatro operaciones sin rutas de evaluación ficticias. */
    @Test @DisplayName("OpenAPI documenta contratos, seguridad y errores de las rutas posteriores")
    void shouldDocumentPostMeasurementOperations() throws Exception {
        var response=send("GET","/v3/api-docs",null,"author");assertEquals(200,response.statusCode());var paths=json.readTree(response.body()).path("paths");
        String root="/api/cycles/{cycleId}/plans/{planId}/measurements";
        for(var route:List.of(root,root+"/reviews",root+"/seal")) {
            var operation=paths.path(route).path("post");assertFalse(operation.path("security").isEmpty());assertTrue(operation.path("responses").has("409"));
        }
        assertFalse(paths.path(root).path("get").path("security").isEmpty());
    }
}
