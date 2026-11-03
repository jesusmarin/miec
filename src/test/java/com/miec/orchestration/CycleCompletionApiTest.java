package com.miec.orchestration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recorrido HTTP autenticado hasta E14 y documentación de roles, comandos y errores. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:completion_api;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "miec.api.author.username=author","miec.api.author.password=closure-author-api-test",
        "miec.api.approver.username=approver","miec.api.approver.password=closure-approver-api-test"})
@DisplayName("MVP-06 — aceptación HTTP de cierre")
class CycleCompletionApiTest {
    @LocalServerPort int port;
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow posts;
    @Autowired CycleCompletionApplicationService service;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    /** Limpia el principal utilizado únicamente para preparar recursos. */
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    /** Petición con Basic real y actor falso que el servidor debe ignorar. */
    private HttpResponse<String> send(String method,String path,Object body,String user)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(20)).header("X-MIEC-Actor","forged");
        if(user!=null)request.header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+(user.equals("author")?"closure-author-api-test":"closure-approver-api-test")).getBytes(StandardCharsets.UTF_8)));
        if(method.equals("GET"))request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    /** Comprueba estado antes de utilizar IDs y versiones de una respuesta. */
    private JsonNode post(String path,Object body,String actor,int status)throws Exception {
        var result=send("POST",path,body,actor);assertEquals(status,result.statusCode(),result.body());return json.readTree(result.body());
    }
    /** Contexto E08 con conjunto cerrado, creado por servicios reales. */
    private CompletionTestSupport.Ready ready(){return CompletionTestSupport.sealed(cycles,measures,intervention,execution,posts);}

    /** Basic, entrada estricta, roles y atomicidad de auditoría se ejercitan sobre el recorrido completo. */
    @Test @DisplayName("Evaluación, decisión, aprendizaje y cierre HTTP sin elevar RS-05")
    void shouldCompleteCycleWithRolesAndSanitizedRollback()throws Exception {
        var r=ready();var f=r.fixture();String root="/api/cycles/"+f.cycleId();String evaluate=root+"/plans/"+f.planId()+"/evaluations";
        var command=Map.of("expectedVersion",r.version(),"setId",r.setId(),"reason","evaluar");
        assertEquals(401,send("POST",evaluate,command,null).statusCode());post(evaluate,command,"approver",403);
        var forged=new LinkedHashMap<String,Object>(command);forged.put("classification","FAVORABLE");post(evaluate,forged,"author",400);
        var admission=post(evaluate,command,"author",201);post(evaluate,command,"author",409);
        String evaluation=admission.path("evaluation").path("report").path("result").path("id").asString();
        var preview=send("GET","/api/evaluations/"+evaluation+"/recommendation",null,"author");assertEquals(200,preview.statusCode());
        assertEquals("COLLECT_EVIDENCE",json.readTree(preview.body()).path("recommendation").path("action").asString());
        var choose=Map.of("expectedVersion",admission.path("completion").path("cycle").path("version").asLong(),"evaluationId",evaluation,"action","CLOSE_CYCLE","acknowledgeLimitations",true,"reason","cierre inconcluso");
        post(root+"/decisions",choose,"author",403);var decision=post(root+"/decisions",choose,"approver",200);
        var learning=Map.of("expectedVersion",decision.path("cycle").path("version").asLong(),"decisionId",decision.path("decision").path("decisionId").asString(),"statement","No se acreditó mejora","conditions","laboratorio","limitations","Sin causalidad","reason","aprender");
        post(root+"/learning",learning,"approver",403);var learned=post(root+"/learning",learning,"author",200);
        var close=Map.of("expectedVersion",learned.path("cycle").path("version").asLong(),"learningId",learned.path("learning").path("learningId").asString(),"summary","Cierre administrativo sin éxito acreditado","acknowledgeLimitations",true,"reason","cerrar");
        post(root+"/close",close,"author",403);
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT test_http_close_audit CHECK(cycle_id<>CAST('"+f.cycleId()+"' AS UUID) OR event_type<>'CYCLE_FINISHED')");
        try {var failure=post(root+"/close",close,"approver",500);assertFalse(failure.toString().contains("test_http_close_audit"));assertFalse(failure.toString().contains("SQL"));}
        finally {jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT test_http_close_audit");}
        var closed=post(root+"/close",close,"approver",200);assertEquals("E14_FINALIZADO",closed.path("cycle").path("state").asString());
        assertEquals("INCONCLUSIVE",closed.path("decision").path("recommendation").path("evaluation").path("classification").asString());
        assertEquals("approver",closed.path("closure").path("closedBy").asString());assertEquals("author",closed.path("learning").path("recordedBy").asString());
        post(root+"/close",close,"approver",409);assertEquals(closed,json.readTree(send("GET",root+"/completion",null,"author").body()));
        SecurityContextHolder.clearContext();assertThrows(LifecycleException.class,()->service.get(f.cycleId()));
    }
    /** Swagger distingue el nuevo Decide y los operadores de las métricas numéricas de evaluación. */
    @Test @DisplayName("OpenAPI documenta rutas, seguridad y esquemas sin colisiones")
    void shouldDocumentCompletionWithDistinctSchemas()throws Exception {
        var response=send("GET","/v3/api-docs",null,"author");assertEquals(200,response.statusCode());var document=json.readTree(response.body());var paths=document.path("paths");
        for(String route:List.of("/api/cycles/{cycleId}/decisions","/api/cycles/{cycleId}/learning","/api/cycles/{cycleId}/close","/api/cycles/{cycleId}/plans/{planId}/evaluations"))
            assertFalse(paths.path(route).path("post").path("security").isEmpty(),route);
        var schemas=document.path("components").path("schemas");assertTrue(schemas.path("CycleHumanDecisionCommand").path("properties").has("evaluationId"));
        assertTrue(schemas.path("MeasurementComparisonOperator").has("enum"));
    }
}
