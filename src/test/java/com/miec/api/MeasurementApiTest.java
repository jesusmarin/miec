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
        "spring.datasource.url=jdbc:h2:mem:measurement_api;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "miec.api.author.username=author","miec.api.author.password=author-mvp02-test-password",
        "miec.api.approver.username=approver","miec.api.approver.password=approver-mvp02-test-password"})
class MeasurementApiTest {
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


    /** Verifica el flujo HTTP medible, consultas y cierre protegido con sus errores de contrato. */
    @Test void shouldRunMeasuredLifecycleOverHttp() throws Exception {
        var system=post("/api/systems",Map.of("name","mediciones HTTP"),null,201);
        var cycle=post("/api/systems/"+system.path("systemId").asString()+"/cycles",Map.of(),null,201);
        String base="/api/cycles/"+cycle.path("cycleId").asString();
        post(base+"/start",Map.of("expectedVersion",0,"reason","inicio"),null,200);
        var initialize=Map.of("expectedVersion",1,"objective","Latencia","successCriteria","p95 <= 100 ms","reason","objetivo",
                "criteria",List.of(Map.of("name","p95","unit","ms","operator","LTE","target",100)));
        assertEquals(401,send("POST",base+"/measurement/initialize",initialize,null).statusCode());
        assertEquals(403,send("POST",base+"/measurement/initialize",initialize,"approver").statusCode());
        post(base+"/measurement/initialize",initialize,"author",200);
        var context=json.readTree(send("GET",base+"/measurement",null,"approver").body());
        assertEquals("MEASURABLE",context.path("mode").asString());
        String indicator=context.path("objectives").get(0).path("criteria").get(0).path("indicatorId").asString();
        post(base+"/measurement/complete",Map.of("expectedVersion",2,"reason","cerrar"),"author",409);
        var measure=Map.of("expectedVersion",2,"indicatorId",indicator,"value",125.5,"unit","ms","source","sensor-1",
                "measuredAt",java.time.Instant.now().minusSeconds(10).toString(),"reason","línea base");
        post(base+"/measurement/baselines",measure,"author",200);
        post(base+"/measurement/baselines",measure,"author",409);
        var closed=post(base+"/measurement/complete",Map.of("expectedVersion",3,"reason","completo"),"author",200);
        assertEquals("E03_DIAGNOSTICANDO",closed.path("cycle").path("state").asString());
        post(base+"/diagnosis",Map.of("expectedVersion",4,"evidenceId",closed.path("resourceId").asString(),"summary","Revisar latencia","reason","diagnóstico"),"author",200);
        var after=json.readTree(send("GET",base+"/measurement",null,"author").body());
        assertEquals(1,after.path("baselines").size());
        assertEquals("author",after.path("baselines").get(0).path("recordedBy").asString());
    }
    /** Swagger y OpenAPI requieren identidad; el esquema expone Basic y las rutas de medición. */
    @Test void shouldProtectSwaggerAndDescribeMeasurementContracts() throws Exception {
        assertEquals(401,send("GET","/v3/api-docs",null,null).statusCode());
        assertEquals(401,send("GET","/v3/api-docs.yaml",null,null).statusCode());
        assertEquals(401,send("GET","/swagger-ui/index.html",null,null).statusCode());
        var response=send("GET","/v3/api-docs",null,"author");
        assertEquals(200,response.statusCode(),response.body()); var document=json.readTree(response.body());
        assertTrue(document.path("paths").has("/api/cycles/{cycleId}/measurement/initialize"));
        assertEquals("basic",document.path("components").path("securitySchemes").path("miecBasic").path("scheme").asString());
        var operation=document.path("paths").path("/api/cycles/{cycleId}/measurement/baselines").path("post");
        assertFalse(operation.path("security").isEmpty()); assertTrue(operation.path("responses").has("409"));
        assertEquals(200,send("GET","/swagger-ui/index.html",null,"approver").statusCode());
    }
}
