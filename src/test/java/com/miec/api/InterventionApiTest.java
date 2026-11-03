package com.miec.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prueba identidades y roles reales por HTTP; las claves son únicamente fixtures de test. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:intervention_api;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "miec.api.author.username=author","miec.api.author.password=author-mvp02-test-password",
        "miec.api.approver.username=approver","miec.api.approver.password=approver-mvp02-test-password"})
class InterventionApiTest {
    @LocalServerPort private int port;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json=JsonMapper.builder().build();

    /** Envía Basic explícito por solicitud e incluye un actor falso para demostrar que no concede autoridad. */
    private HttpResponse<String> send(String method,String path,Object body,String user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
                .header("X-MIEC-Actor","forged-identity");
        if(user!=null) {
            String password=user.equals("author")?"author-mvp02-test-password":user.equals("approver")?"approver-mvp02-test-password":"wrong";
            request.header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8)));
        }
        if(method.equals("GET")) request.GET();
        else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    /** Exige el estado HTTP esperado antes de utilizar una respuesta como dato del siguiente paso. */
    private JsonNode post(String path,Object body,String user,int status) throws Exception {
        var response=send("POST",path,body,user); assertEquals(status,response.statusCode(),response.body()); return json.readTree(response.body());
    }

    /** Prepara el plan íntegramente desde las APIs y las reglas de estados existentes. */
    private JsonNode plan(int actions) throws Exception {
        var system=post("/api/systems",Map.of("name","MVP-02 HTTP"),null,201);
        var cycle=post("/api/systems/"+system.path("systemId").asString()+"/cycles",Map.of(),null,201);
        String base="/api/cycles/"+cycle.path("cycleId").asString();
        post(base+"/start",Map.of("expectedVersion",0,"reason","inicio"),null,200);
        var objective=post(base+"/initialize",Map.of("expectedVersion",1,"objective","Reducir fallos","successCriteria","Prueba satisfactoria","reason","inicializar"),"author",200);
        var evidence=post(base+"/observations",Map.of("expectedVersion",2,"description","Fallo observado","reason","observar"),"author",200);
        post(base+"/diagnosis",Map.of("expectedVersion",3,"evidenceId",evidence.path("resourceId").asString(),"summary","Ajustar proceso","reason","diagnosticar"),"author",200);
        return post(base+"/plans",Map.of("expectedVersion",4,"objectiveId",objective.path("resourceId").asString(),
                "expectedOutcomes","Prueba correcta","rollbackStrategy","Restaurar","actions",
                java.util.stream.IntStream.range(0,actions).mapToObj(i->Map.of("actionType","PROCESS_EXECUTION","description","acción "+i)).toList()),"author",201);
    }

    /** Anónimos y credenciales falsas no acceden; un aprobador no puede actuar como autor. */
    @Test void shouldEnforceAuthenticationAndRoles() throws Exception {
        String path="/api/plans/"+UUID.randomUUID();
        assertEquals(401,send("GET",path,null,null).statusCode());
        var wrong=send("GET",path,null,"unknown");
        assertEquals(401,wrong.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED",json.readTree(wrong.body()).path("code").asString());
        assertEquals(404,send("GET",path,null,"author").statusCode());
        assertEquals(404,send("GET",path,null,"approver").statusCode());
        assertEquals(403,send("POST","/api/cycles/"+UUID.randomUUID()+"/initialize",
                Map.of("expectedVersion",1,"objective","o","successCriteria","c","reason","r"),"approver").statusCode());
    }

    /** La atribución viene del principal autenticado aunque X-MIEC-Actor contenga otra identidad. */
    @Test void shouldUseAuthenticatedIdentityAndRejectForgedApprovalFields() throws Exception {
        var plan=plan(1); assertEquals("author",plan.path("createdBy").asString());
        var contextResponse=send("GET","/api/plans/"+plan.path("planId").asString()+"/context",null,"approver");
        assertEquals(200,contextResponse.statusCode());
        var context=json.readTree(contextResponse.body());
        assertEquals("Reducir fallos",context.path("objective").path("name").asString());
        assertEquals("Prueba satisfactoria",context.path("objective").path("successCriteria").asString());
        assertEquals("Ajustar proceso",context.path("diagnosis").path("summary").asString());
        assertEquals("UNVERIFIED",context.path("diagnosis").path("qualityStatus").asString());
        assertEquals(plan.path("fingerprint").asString(),context.path("planFingerprint").asString());
        String path="/api/plans/"+plan.path("planId").asString()+"/authorization-requests";
        String action=plan.path("actions").get(0).path("actionId").asString();
        assertEquals(400,send("POST",path,Map.of("expectedPlanVersion",0,"actionId",action,"validForSeconds",600,"approvedBy","forged"),"author").statusCode());
        var request=post(path,Map.of("expectedPlanVersion",0,"actionId",action,"validForSeconds",600),"author",201);
        assertEquals("author",request.path("requestedBy").asString());
        assertEquals("PENDING",request.path("status").asString());
        assertTrue(request.path("approvedBy").isNull());
        assertEquals(200,send("GET","/api/authorizations/"+request.path("authorizationId").asString(),null,"approver").statusCode());
    }

    /** La aprobación HTTP proviene del rol correcto; un autor no puede decidir aunque falsifique X-MIEC-Actor. */
    @Test void shouldApprovePlanWithAuthenticatedApprover() throws Exception {
        var plan=plan(1);String planId=plan.path("planId").asString();
        var request=post("/api/plans/"+planId+"/authorization-requests",Map.of("expectedPlanVersion",0,
                "actionId",plan.path("actions").get(0).path("actionId").asString(),"validForSeconds",600),"author",201);
        String path="/api/authorizations/"+request.path("authorizationId").asString();
        assertEquals(403,send("POST",path+"/approve",Map.of("expectedVersion",0,"reason","suplantar"),"author").statusCode());
        assertEquals(400,send("POST",path+"/approve",Map.of("expectedVersion",0,"reason","r","approvedBy","author"),"approver").statusCode());
        var approved=post(path+"/approve",Map.of("expectedVersion",0,"reason","revisado por persona"),"approver",200);
        assertEquals("APPROVED",approved.path("status").asString());
        assertEquals("approver",approved.path("approvedBy").asString());
        assertEquals("approver",approved.path("decision").path("decidedBy").asString());
        assertEquals(409,send("POST",path+"/reject",Map.of("expectedVersion",0,"reason","repetido"),"approver").statusCode());
        var cycle=send("GET","/api/cycles/"+plan.path("cycleId").asString(),null,null);
        assertEquals("E06_PREPARADO",json.readTree(cycle.body()).path("state").asString());
    }

    /** Un rechazo HTTP conserva el motivo y deja el ciclo suspendido, sin permisos de ejecución. */
    @Test void shouldRejectPlanOverHttp() throws Exception {
        var plan=plan(1);
        var request=post("/api/plans/"+plan.path("planId").asString()+"/authorization-requests",Map.of("expectedPlanVersion",0,
                "actionId",plan.path("actions").get(0).path("actionId").asString(),"validForSeconds",600),"author",201);
        var rejected=post("/api/authorizations/"+request.path("authorizationId").asString()+"/reject",
                Map.of("expectedVersion",0,"reason","riesgo no aceptado"),"approver",200);
        assertEquals("REJECTED",rejected.path("status").asString());
        assertEquals("riesgo no aceptado",rejected.path("decision").path("reason").asString());
        assertEquals("E12_SUSPENDIDO",json.readTree(send("GET","/api/cycles/"+plan.path("cycleId").asString(),null,null).body()).path("state").asString());
    }

    /** Varias acciones necesitan todas sus revisiones; una aprobación parcial no produce E06. */
    @Test void shouldRequireAllActionApprovalsOverHttp() throws Exception {
        var plan=plan(2);String planId=plan.path("planId").asString();
        var first=post("/api/plans/"+planId+"/authorization-requests",Map.of("expectedPlanVersion",0,
                "actionId",plan.path("actions").get(0).path("actionId").asString(),"validForSeconds",600),"author",201);
        var second=post("/api/plans/"+planId+"/authorization-requests",Map.of("expectedPlanVersion",1,
                "actionId",plan.path("actions").get(1).path("actionId").asString(),"validForSeconds",600),"author",201);
        var partial=post("/api/authorizations/"+first.path("authorizationId").asString()+"/approve",Map.of("expectedVersion",0,"reason","primera"),"approver",200);
        assertEquals("APPROVED_PENDING_PLAN",partial.path("status").asString());
        assertEquals("E05_ESPERANDO_AUTORIZACION",json.readTree(send("GET","/api/cycles/"+plan.path("cycleId").asString(),null,null).body()).path("state").asString());
        post("/api/authorizations/"+second.path("authorizationId").asString()+"/approve",Map.of("expectedVersion",0,"reason","segunda"),"approver",200);
        assertEquals("APPROVED",json.readTree(send("GET","/api/authorizations/"+first.path("authorizationId").asString(),null,"author").body()).path("status").asString());
    }
}
