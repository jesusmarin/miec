package com.miec.api;

import com.miec.orchestration.ExecutionTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** APIs autenticadas hasta ejecución real IR-009 con un destinatario de test instalado por configuración. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:plan_execution_api;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "miec.api.author.username=author","miec.api.author.password=author-execution-api-test",
        "miec.api.approver.username=approver","miec.api.approver.password=approver-execution-api-test"})
class PlanExecutionApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json=JsonMapper.builder().build();

    /** Envía Basic real y un actor falso para comprobar que el coordinador utiliza el principal del servidor. */
    private HttpResponse<String> send(String method,String path,Object body,String user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("X-MIEC-Actor","forged");
        if(user!=null) request.header("Authorization","Basic "+Base64.getEncoder().encodeToString(
                (user+":"+(user.equals("author")?"author-execution-api-test":"approver-execution-api-test")).getBytes(StandardCharsets.UTF_8)));
        if(method.equals("GET")) request.GET();
        else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    /** Exige el estado esperado antes de utilizar IDs o versiones de una respuesta. */
    private JsonNode post(String path,Object body,String role,int status) throws Exception {
        var response=send("POST",path,body,role); assertEquals(status,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    /** Consulta con autorización de lectura y sin modificar el recurso. */
    private JsonNode get(String path) throws Exception {
        var response=send("GET",path,null,"author");assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());
    }
    /** Prepara y aprueba acciones por HTTP; ninguna definición se añade después de aprobar. */
    private JsonNode approved(int count,boolean executable) throws Exception {
        var system=post("/api/systems",Map.of("name","ejecución HTTP"),"author",201);
        var cycle=post("/api/systems/"+system.path("systemId").asString()+"/cycles",Map.of(),"author",201);
        String path="/api/cycles/"+cycle.path("cycleId").asString();
        post(path+"/start",Map.of("expectedVersion",0,"reason","inicio"),"author",200);
        var objective=post(path+"/initialize",Map.of("expectedVersion",1,"objective","objetivo","successCriteria","criterio","reason","r"),"author",200);
        var evidence=post(path+"/observations",Map.of("expectedVersion",2,"description","dato","reason","r"),"author",200);
        post(path+"/diagnosis",Map.of("expectedVersion",3,"evidenceId",evidence.path("resourceId").asString(),"summary","diagnóstico","reason","r"),"author",200);
        var actions=new ArrayList<Map<String,Object>>();
        for(int index=0;index<count;index++) {
            var action=new LinkedHashMap<String,Object>(); action.put("actionType","PROCESS_EXECUTION");action.put("description","Paso "+index);
            if(executable) action.put("execution",Map.of("adapterId","test-recipient","adapterVersion","1","destinationId","test-destination","mediaType","application/json","body","{\"step\":"+index+"}"));
            actions.add(action);
        }
        var plan=post(path+"/plans",Map.of("expectedVersion",4,"objectiveId",objective.path("resourceId").asString(),"expectedOutcomes","resultado","rollbackStrategy","restaurar","actions",actions),"author",201);
        String planPath="/api/plans/"+plan.path("planId").asString();var requests=new ArrayList<JsonNode>();
        for(var action:plan.path("actions")) requests.add(post(planPath+"/authorization-requests",Map.of("expectedPlanVersion",get(planPath).path("version").asLong(),"actionId",action.path("actionId").asString(),"validForSeconds",600),"author",201));
        for(var request:requests) post("/api/authorizations/"+request.path("authorizationId").asString()+"/approve",Map.of("expectedVersion",0,"reason","aprobado"),"approver",200);
        return get(planPath);
    }
    /** Construye la ruta de inicio desde IDs persistidos; no entrega acciones o permisos en el comando. */
    private String startPath(JsonNode plan) { return "/api/cycles/"+plan.path("cycleId").asString()+"/plans/"+plan.path("planId").asString()+"/execution"; }
    /** Versiones actuales de ciclo y plan para el comando de inicio. */
    private Map<String,Object> startBody(JsonNode plan) throws Exception {
        return Map.of("expectedCycleVersion",get("/api/cycles/"+plan.path("cycleId").asString()).path("version").asLong(),"expectedPlanVersion",plan.path("version").asLong(),"reason","ejecutar contenido aprobado");
    }

    /** Devuelve coordinación durable y Location; repetir inicio no crea nuevos intentos. */
    @Test void shouldExecuteOrderedPlanAndExposeProgressWithLocation() throws Exception {
        var plan=approved(2,true);String readPath="/api/plans/"+plan.path("planId").asString()+"/execution";
        var body=startBody(plan);var response=send("POST",startPath(plan),body,"author");
        assertEquals(201,response.statusCode(),response.body());assertEquals(readPath,response.headers().firstValue("Location").orElseThrow());
        var run=json.readTree(response.body());assertEquals("APPLIED",run.path("state").asString());assertEquals("author",run.path("createdBy").asString());
        assertEquals("E07_EJECUTANDO",run.path("cycleState").asString());assertEquals(2,run.path("steps").size());
        assertEquals(200,send("GET",readPath,null,"approver").statusCode());post(startPath(plan),body,"author",409);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM execution_attempt WHERE plan_id=?",Integer.class,UUID.fromString(plan.path("planId").asString())));
    }

    /** Filtros y fachada coinciden: autor ejecuta, aprobador recupera y ninguno acepta identidades del cuerpo. */
    @Test void shouldEnforceRolesAndRejectForgedExecutionCommands() throws Exception {
        var plan=approved(1,true);var body=startBody(plan);
        assertEquals(401,send("POST",startPath(plan),body,null).statusCode());post(startPath(plan),body,"approver",403);
        var forged=new LinkedHashMap<>(body);forged.put("actorId","approver");post(startPath(plan),forged,"author",400);
        String recover="/api/plans/"+plan.path("planId").asString()+"/execution/recover";
        post(recover,Map.of("expectedVersion",0,"reason","r"),"author",403);
        post(startPath(plan),Map.of("expectedCycleVersion",0,"expectedPlanVersion",plan.path("version").asLong(),"reason","vieja"),"author",409);
        assertEquals("E06_PREPARADO",get("/api/cycles/"+plan.path("cycleId").asString()).path("state").asString());
    }

    /** Un plan descriptivo histórico conserva consulta y aprobación pero no se convierte en ejecutable. */
    @Test void shouldKeepDescriptivePlanNonExecutable() throws Exception {
        var plan=approved(1,false);var response=post(startPath(plan),startBody(plan),"author",409);
        assertEquals("EXECUTABLE_BINDING_INVALID",response.path("code").asString());
        assertEquals("E06_PREPARADO",get("/api/cycles/"+plan.path("cycleId").asString()).path("state").asString());
    }

    /** Catálogo sin secretos y documentación OpenAPI muestran contratos y requisito Basic de cada operación nueva. */
    @Test void shouldDocumentExecutionApisAndPublishOnlyPublicAdapterMetadata() throws Exception {
        assertEquals(401,send("GET","/api/execution-adapters",null,null).statusCode());
        var installed=get("/api/execution-adapters");assertEquals("test-recipient",installed.get(0).path("adapterId").asString());
        assertFalse(installed.get(0).has("apiKey"));assertFalse(installed.get(0).has("baseUrl"));
        var paths=get("/v3/api-docs").path("paths");
        var operation=paths.path("/api/cycles/{cycleId}/plans/{planId}/execution").path("post");
        assertFalse(operation.path("security").isEmpty());assertTrue(operation.path("responses").has("409"));
        assertTrue(paths.has("/api/plans/{planId}/execution/recover"));
    }
}
