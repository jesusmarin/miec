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
        var register=Map.of("expectedCycleVersion",learned.path("cycle").path("version").asLong(),"reason","initial round");
        assertEquals(401,send("POST",root+"/rounds/initial",register,null).statusCode());
        post(root+"/rounds/initial",register,"approver",403);
        var forgedRound=new LinkedHashMap<String,Object>(register);forgedRound.put("registeredBy","forged");post(root+"/rounds/initial",forgedRound,"author",400);
        var registered=post(root+"/rounds/initial",register,"author",200);post(root+"/rounds/initial",register,"author",409);
        var round=registered.path("rounds").get(0);assertEquals("author",round.path("registeredBy").asString());
        String conclude=root+"/rounds/"+round.path("roundId").asString()+"/plan-completion";
        var finishPlan=Map.of("expectedCycleVersion",registered.path("cycle").path("version").asLong(),"expectedRoundVersion",0,"reason","operational completion");
        post(conclude,finishPlan,"author",403);var concluded=post(conclude,finishPlan,"approver",200);
        assertTrue(concluded.path("activePlanIds").isEmpty());assertEquals("approver",concluded.path("rounds").get(0).path("conclusion").path("recordedBy").asString());
        assertEquals(concluded,json.readTree(send("GET",root+"/rounds",null,"author").body()));
        var close=Map.of("expectedVersion",concluded.path("cycle").path("version").asLong(),"learningId",learned.path("learning").path("learningId").asString(),"summary","Cierre administrativo sin éxito acreditado","acknowledgeLimitations",true,"reason","cerrar");
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
        for(String route:List.of("/api/cycles/{cycleId}/rounds/initial","/api/cycles/{cycleId}/rounds/{roundId}/plan-completion","/api/cycles/{cycleId}/decisions","/api/cycles/{cycleId}/learning","/api/cycles/{cycleId}/close","/api/cycles/{cycleId}/plans/{planId}/evaluations"))
            assertFalse(paths.path(route).path("post").path("security").isEmpty(),route);
        var schemas=document.path("components").path("schemas");assertTrue(schemas.path("CycleHumanDecisionCommand").path("properties").has("evaluationId"));
        assertTrue(schemas.path("MeasurementComparisonOperator").has("enum"));
        assertTrue(schemas.path("RoundContextV1").path("properties").has("rounds"));
        assertTrue(schemas.path("Context").path("properties").has("baselines"));
        assertTrue(schemas.path("RoundHistoricalSnapshotV1").path("properties").has("evaluation"));
        assertTrue(schemas.path("Snapshot").path("properties").has("pairs"));
        for(String route:List.of("/api/cycles/{cycleId}/rounds/{roundId}/continuations","/api/cycles/{cycleId}/rounds/continuations/{requestId}/approve"))
            assertFalse(paths.path(route).path("post").path("security").isEmpty());
        assertTrue(schemas.path("RequestContinuation").path("properties").has("baselines"));
        assertTrue(schemas.path("ApproveContinuation").path("properties").has("expectedRequestVersion"));
    }
    /** Resultado de aprendizaje y conclusión con sus versiones actuales. */
    private record HttpRound(JsonNode learned,JsonNode rounds) { }
    /** Evalúa, decide, aprende y concluye el plan actual exclusivamente por HTTP. */
    private HttpRound finishRound(String root,String plan,JsonNode measurements,String action)throws Exception {
        var admitted=post(root+"/plans/"+plan+"/evaluations",Map.of("expectedVersion",measurements.path("cycleVersion").asLong(),"setId",measurements.path("set").path("setId").asString(),"reason","evaluar ronda"),"author",201);
        var decided=post(root+"/decisions",Map.of("expectedVersion",admitted.path("completion").path("cycle").path("version").asLong(),"evaluationId",admitted.path("evaluation").path("report").path("result").path("id").asString(),"action",action,"acknowledgeLimitations",true,"reason","selección humana"),"approver",200);
        var learned=post(root+"/learning",Map.of("expectedVersion",decided.path("cycle").path("version").asLong(),"decisionId",decided.path("decision").path("decisionId").asString(),"statement","aprendizaje","conditions","laboratorio","limitations","Sin causalidad","reason","aprender"),"author",200);
        var context=json.readTree(send("GET",root+"/rounds",null,"author").body());
        if(context.path("rounds").isEmpty())context=post(root+"/rounds/initial",Map.of("expectedCycleVersion",learned.path("cycle").path("version").asLong(),"reason","primera ronda"),"author",200);
        var current=context.path("rounds").get(context.path("rounds").size()-1);
        context=post(root+"/rounds/"+current.path("roundId").asString()+"/plan-completion",Map.of("expectedCycleVersion",context.path("cycle").path("version").asLong(),"expectedRoundVersion",current.path("version").asLong(),"reason","concluir plan"),"approver",200);
        return new HttpRound(learned,context);
    }
    /** La API completa dos rondas con roles, fuentes y permisos independientes. */
    @Test @DisplayName("Dos rondas HTTP con continuidad aprobada, permisos nuevos y cierre E14")
    void shouldCompleteTwoRoundsOverHttp()throws Exception {
        var ready=ready();var f=ready.fixture();String root="/api/cycles/"+f.cycleId();
        var firstContext=json.readTree(send("GET",root+"/plans/"+f.planId()+"/measurements",null,"author").body());
        var first=finishRound(root,f.planId().toString(),firstContext,"COLLECT_EVIDENCE");var old=first.rounds().path("rounds").get(0);String roundId=old.path("roundId").asString();
        var command=Map.of("expectedCycleVersion",first.rounds().path("cycle").path("version").asLong(),"expectedRoundVersion",old.path("version").asLong(),
            "decisionId",first.learned().path("decision").path("decisionId").asString(),"learningId",first.learned().path("learning").path("learningId").asString(),"mode","COLLECT_EVIDENCE","acknowledgeLimitations",true,"reason","continuar","baselines",List.of());
        String requestPath=root+"/rounds/"+roundId+"/continuations";assertEquals(401,send("POST",requestPath,command,null).statusCode());post(requestPath,command,"approver",403);
        var forged=new LinkedHashMap<String,Object>(command);forged.put("state","E04_PLANIFICANDO");post(requestPath,forged,"author",400);
        var request=post(requestPath,command,"author",200);String approve=root+"/rounds/continuations/"+request.path("requestId").asString()+"/approve";
        var approval=Map.of("expectedCycleVersion",request.path("cycleVersion").asLong(),"expectedRoundVersion",request.path("roundVersion").asLong(),"expectedRequestVersion",request.path("version").asLong(),"acknowledgeLimitations",true,"reason","aprobar continuidad");
        post(approve,approval,"author",403);var continued=post(approve,approval,"approver",200);post(approve,approval,"approver",409);
        assertEquals("E02_OBSERVANDO",continued.path("cycle").path("state").asString());assertEquals(old.path("snapshot"),continued.path("rounds").get(0).path("snapshot"));
        var context=json.readTree(send("GET",root+"/measurement",null,"author").body());assertTrue(context.path("baselines").isEmpty());
        assertEquals(1,json.readTree(send("GET",root+"/rounds/"+roundId+"/measurement",null,"author").body()).path("baselines").size());
        var objective=context.path("objectives").get(0);var indicator=objective.path("criteria").get(0);String indicatorId=indicator.path("indicatorId").asString();
        var baseline=post(root+"/measurement/baselines",Map.of("expectedVersion",continued.path("cycle").path("version").asLong(),"indicatorId",indicatorId,"value",125,"unit","ms","source","muestra nueva","measuredAt",PostMeasurementTestSupport.now().toString(),"reason","base ronda dos"),"author",200);
        var observed=post(root+"/measurement/complete",Map.of("expectedVersion",baseline.path("cycle").path("version").asLong(),"reason","cerrar nueva base"),"author",200);
        var diagnosed=post(root+"/diagnosis",Map.of("expectedVersion",observed.path("cycle").path("version").asLong(),"evidenceId",observed.path("resourceId").asString(),"summary","diagnóstico nuevo","reason","diagnosticar"),"author",200);
        var spec=Map.of("adapterId","test-recipient","adapterVersion","1","destinationId","test-destination","mediaType","application/json","body","{}");
        var plan=post(root+"/plans",Map.of("expectedVersion",diagnosed.path("cycle").path("version").asLong(),"objectiveId",objective.path("objectiveId").asString(),"expectedOutcomes","nuevo resultado","rollbackStrategy","restaurar","actions",List.of(Map.of("actionType","PROCESS_EXECUTION","description","ronda dos","execution",spec))),"author",201);
        String planId=plan.path("planId").asString();var permission=post("/api/plans/"+planId+"/authorization-requests",Map.of("expectedPlanVersion",plan.path("version").asLong(),"actionId",plan.path("actions").get(0).path("actionId").asString(),"validForSeconds",600),"author",201);
        post("/api/authorizations/"+permission.path("authorizationId").asString()+"/approve",Map.of("expectedVersion",permission.path("version").asLong(),"reason","nueva aprobación"),"approver",200);
        plan=json.readTree(send("GET","/api/plans/"+planId,null,"author").body());var cycle=json.readTree(send("GET",root,null,"author").body());
        var run=post(root+"/plans/"+planId+"/execution",Map.of("expectedCycleVersion",cycle.path("version").asLong(),"expectedPlanVersion",plan.path("version").asLong(),"reason","ejecutar ronda dos"),"author",201);
        cycle=json.readTree(send("GET",root,null,"author").body());var finished=post("/api/plans/"+planId+"/execution/finish",Map.of("expectedVersion",run.path("version").asLong(),"expectedCycleVersion",cycle.path("version").asLong(),"reason","confirmar"),"approver",200);
        var posts=post(root+"/plans/"+planId+"/measurements",Map.of("runId",run.path("runId").asString(),"method","procedimiento v1","measurement",Map.of("expectedVersion",json.readTree(send("GET",root,null,"author").body()).path("version").asLong(),"indicatorId",indicatorId,"value",80,"unit","ms","source","informe nuevo","measuredAt",PostMeasurementTestSupport.now().toString(),"reason","medir resultado")),"author",200);
        var sample=posts.path("measurements").get(0);var base=json.readTree(send("GET",root+"/measurement",null,"author").body()).path("baselines").get(0);
        var sealed=post(root+"/plans/"+planId+"/measurements/seal",Map.of("expectedVersion",posts.path("cycleVersion").asLong(),"runId",run.path("runId").asString(),"method","procedimiento v1","baselineFrom",base.path("measuredAt").asString(),"baselineTo",base.path("measuredAt").asString(),"postFrom",sample.path("measuredAt").asString(),"postTo",PostMeasurementTestSupport.now().toString(),"pairs",List.of(Map.of("indicatorId",indicatorId,"baselineId",base.path("baselineId").asString(),"measurementId",sample.path("measurementId").asString())),"reason","conjunto ronda dos"),"approver",200);
        var second=finishRound(root,planId,sealed,"CLOSE_CYCLE");var closed=post(root+"/close",Map.of("expectedVersion",second.rounds().path("cycle").path("version").asLong(),"learningId",second.learned().path("learning").path("learningId").asString(),"summary","dos rondas","acknowledgeLimitations",true,"reason","cerrar"),"approver",200);
        assertEquals("E14_FINALIZADO",closed.path("cycle").path("state").asString());assertEquals("INCONCLUSIVE",closed.path("decision").path("recommendation").path("evaluation").path("classification").asString());
        var history=json.readTree(send("GET",root+"/rounds",null,"author").body());assertEquals(2,history.path("rounds").size());assertTrue(history.path("activePlanIds").isEmpty());assertEquals(old.path("snapshot"),history.path("rounds").get(0).path("snapshot"));
    }
}
