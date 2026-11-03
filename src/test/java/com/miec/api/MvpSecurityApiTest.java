package com.miec.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.ServletContext;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Seguridad real de HTTP en las rutas históricas, sin desactivar filtros ni usar usuarios simulados. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:mvp_uniform_security;DB_CLOSE_DELAY=-1",
        "server.address=127.0.0.1","spring.profiles.active=default",
        "miec.api.author.username=author","miec.api.author.password=author-security-test-password",
        "miec.api.approver.username=approver","miec.api.approver.password=approver-security-test-password"})
class MvpSecurityApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ServletContext servletContext;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json=JsonMapper.builder().build();

    /** Envía una cabecera de actor falsificada que nunca debe conceder acceso ni aparecer en eventos. */
    private HttpResponse<String> request(String method,String path,String body,String user,String contentType) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
                .header("X-MIEC-Actor","forged-administrator");
        if(user!=null) {
            String password=user.equals("author")?"author-security-test-password":user.equals("approver")?"approver-security-test-password":"invalid";
            builder.header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8)));
        }
        if(contentType!=null) builder.header("Content-Type",contentType);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    /** Crear un recurso con AUTHOR permite comprobar luego las consultas del aprobador. */
    private String system() throws Exception {
        var response=request("POST","/api/systems","{\"name\":\"seguridad\"}","author","application/json");
        assertEquals(201,response.statusCode(),response.body());
        return json.readTree(response.body()).path("systemId").asString();
    }

    /** Anónimos o claves falsas no consultan ni mutan las rutas anteriores; tampoco basta un actor declarado. */
    @Test void shouldProtectEveryLegacyRouteWithoutWritingAnything() throws Exception {
        UUID id=UUID.randomUUID();
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM system_objective",Integer.class);
        for(String path:new String[]{"/api/systems/"+id,"/api/cycles/"+id,"/api/cycles/"+id+"/history","/v3/api-docs","/v3/api-docs.yaml","/swagger-ui/index.html"}) {
            var response=request("GET",path,null,null,null);
            assertEquals(401,response.statusCode(),path); assertTrue(response.headers().firstValue("WWW-Authenticate").isPresent());
        }
        for(String path:new String[]{"/api/systems","/api/systems/"+id+"/cycles","/api/cycles/"+id+"/start"})
            assertEquals(401,request("POST",path,"{}",null,"application/json").statusCode(),path);
        var wrong=request("POST","/api/systems","{\"name\":\"no\"}","wrong","application/json");
        assertEquals(401,wrong.statusCode()); assertFalse(wrong.body().contains("invalid"));
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM system_objective",Integer.class));
    }

    /** El aprobador consulta sistemas/ciclos, pero crear o iniciar requiere AUTHOR. */
    @Test void shouldApplyRoleMatrixToLegacyMutationsAndReads() throws Exception {
        String system=system();
        assertEquals(200,request("GET","/api/systems/"+system,null,"approver",null).statusCode());
        assertEquals(403,request("POST","/api/systems","{\"name\":\"no\"}","approver","application/json").statusCode());
        assertEquals(403,request("POST","/api/systems/"+system+"/cycles","{}","approver","application/json").statusCode());
        var created=request("POST","/api/systems/"+system+"/cycles","{}","author","application/json");
        assertEquals(201,created.statusCode(),created.body()); String cycle=json.readTree(created.body()).path("cycleId").asString();
        assertEquals(200,request("GET","/api/cycles/"+cycle,null,"approver",null).statusCode());
        assertEquals(403,request("POST","/api/cycles/"+cycle+"/start","{\"expectedVersion\":0,\"reason\":\"no\"}","approver","application/json").statusCode());
        assertEquals("E00_INACTIVO",jdbc.queryForObject("SELECT state FROM cycle WHERE cycle_id=?",String.class,UUID.fromString(cycle)));
    }

    /** La identidad autenticada aparece tanto en creación como en inicio aunque se falsifique la cabecera. */
    @Test void shouldAttributeEventsToAuthenticatedAuthor() throws Exception {
        String system=system();
        var created=request("POST","/api/systems/"+system+"/cycles","{}","author","application/json");
        assertEquals(201,created.statusCode()); String cycle=json.readTree(created.body()).path("cycleId").asString();
        var started=request("POST","/api/cycles/"+cycle+"/start","{\"expectedVersion\":0,\"reason\":\"inicio\"}","author","application/json");
        assertEquals(200,started.statusCode(),started.body());
        var actors=jdbc.queryForList("SELECT actor_id FROM cycle_event WHERE cycle_id=?",String.class,UUID.fromString(cycle));
        assertEquals(2,actors.size()); assertTrue(actors.stream().allMatch("author"::equals));
        assertEquals(200,request("GET","/api/cycles/"+cycle+"/history",null,"approver",null).statusCode());
    }

    /** No existen escrituras por formulario ni métodos nuevos habilitados implícitamente. */
    @Test void shouldRejectFormsUnsupportedMethodsAndActorInBody() throws Exception {
        assertEquals(415,request("POST","/api/systems","name=unsafe","author","application/x-www-form-urlencoded").statusCode());
        assertEquals(400,request("POST","/api/systems","{\"name\":\"s\",\"actorId\":\"forged\"}","author","application/json").statusCode());
        assertEquals(403,request("DELETE","/api/systems/"+UUID.randomUUID(),null,"author",null).statusCode());
        assertEquals(403,request("GET","/unregistered-route",null,"author",null).statusCode());
    }

    /** La consola no se registra por defecto y ni AUTHOR puede abrir una ruta fuera de la API permitida. */
    @Test void shouldDisableDevelopmentConsoleByDefault() throws Exception {
        assertTrue(servletContext.getServletRegistrations().values().stream().noneMatch(r->r.getClassName().contains("h2.server.web")));
        assertEquals(403,request("GET","/h2-console/",null,"author",null).statusCode());
        assertEquals(403,request("GET","/h2-console/login.do",null,"approver",null).statusCode());
    }

    /** Swagger identifica Basic también en las rutas históricas, sin sugerir acceso público. */
    @Test void shouldDocumentAuthenticationForLegacyOperations() throws Exception {
        var response=request("GET","/v3/api-docs",null,"approver",null);
        assertEquals(200,response.statusCode(),response.body()); var paths=json.readTree(response.body()).path("paths");
        assertFalse(paths.path("/api/systems").path("post").path("security").isEmpty());
        assertFalse(paths.path("/api/cycles/{cycleId}/start").path("post").path("security").isEmpty());
        assertFalse(paths.path("/api/cycles/{cycleId}/history").path("get").path("security").isEmpty());
    }
}
