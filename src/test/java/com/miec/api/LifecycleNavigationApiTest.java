package com.miec.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP real para descubrimiento, permisos, errores y contrato OpenAPI del MVP-09. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:navigation_api;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "miec.api.author.username=nav-author","miec.api.author.password=navigation-author-password",
        "miec.api.approver.username=nav-approver","miec.api.approver.password=navigation-approver-password"})
@DisplayName("MVP-09 - navegacion HTTP autenticada y OpenAPI")
class LifecycleNavigationApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Credenciales fijas solo del servidor efimero; null ejercita el rechazo anonimo. */
    private String credentials(String role){
        if(role==null)return null;
        String raw=role.equals("AUTHOR")?"nav-author:navigation-author-password":"nav-approver:navigation-approver-password";
        return "Basic "+Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    /** Envia solicitudes sin cabeceras de identidad del cliente que puedan alterar autorizacion. */
    private HttpResponse<String> request(String path,Object body,String role)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(role!=null)builder.header("Authorization",credentials(role));
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    /** Comprueba el estado HTTP antes de interpretar el cuerpo de un comando o consulta. */
    private JsonNode checked(String path,Object body,String role,int status)throws Exception{
        var response=request(path,body,role);assertEquals(status,response.statusCode(),response.body());return json.readTree(response.body());
    }
    /** Crea un recurso por la API real para que la navegacion pueda descubrirlo posteriormente. */
    private JsonNode system(String name)throws Exception{return checked("/api/systems",Map.of("name",name),"AUTHOR",201);}
    /** Crea ciclos de pruebas sin modificar estados mediante SQL. */
    private JsonNode cycle(String id)throws Exception{return checked("/api/systems/"+id+"/cycles",Map.of(),"AUTHOR",201);}
    /** Codifica nombres y cursores como valores, nunca como segmentos de SQL o rutas arbitrarias. */
    private String encoded(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}

    /** Ambos roles descubren recursos y las representaciones de detalle existentes permanecen iguales. */
    @Test @DisplayName("AUTHOR y APPROVER listan sistemas y ciclos sin cambiar los contratos de detalle")
    void listsResourcesAndPreservesDetails()throws Exception{
        String name="nav-http-"+UUID.randomUUID();var system=system(name);String id=system.path("systemId").asString();
        var first=cycle(id);var second=cycle(id);
        for(String role:List.of("AUTHOR","APPROVER")){
            var page=checked("/api/systems?q="+encoded(name),null,role,200);assertEquals(1,page.path("items").size());
            var item=page.path("items").get(0);assertEquals(id,item.path("systemId").asString());assertEquals(2,item.path("cycleCount").asLong());
            assertEquals(2,item.path("cyclesByState").path("E00_INACTIVO").asLong());assertFalse(item.path("lastActivityAt").isNull());
            var cycles=checked("/api/systems/"+id+"/cycles",null,role,200);assertEquals(2,cycles.path("items").size());
            Set<JsonNode> expected=Set.of(first,second);assertEquals(expected,Set.of(cycles.path("items").get(0),cycles.path("items").get(1)));
            assertEquals(system,checked("/api/systems/"+id,null,role,200));
            assertEquals(first,checked("/api/cycles/"+first.path("cycleId").asString(),null,role,200));
        }
    }

    /** El catalogo global conserva la seguridad actual; no permite escribir al rol lector APPROVER. */
    @Test @DisplayName("Listados sin autenticacion devuelven 401 y APPROVER no adquiere permisos de creacion")
    void requiresReaderIdentity()throws Exception{
        String id=system("nav-auth-"+UUID.randomUUID()).path("systemId").asString();
        assertEquals(401,request("/api/systems",null,null).statusCode());
        assertEquals(401,request("/api/systems/"+id+"/cycles",null,null).statusCode());
        assertEquals(403,request("/api/systems",Map.of("name","forbidden"),"APPROVER").statusCode());
        assertEquals(403,request("/api/systems/"+id+"/cycles",Map.of(),"APPROVER").statusCode());
    }

    /** Sistema inexistente y sistema sin ciclos se distinguen sin cargar relaciones inexistentes. */
    @Test @DisplayName("Paginas vacias usan 200, padre inexistente 404 y UUID malformado 400")
    void distinguishesEmptyAndMissing()throws Exception{
        String name="nav-empty-"+UUID.randomUUID();String id=system(name).path("systemId").asString();
        var summary=checked("/api/systems?q="+encoded(name),null,"AUTHOR",200).path("items").get(0);
        assertEquals(0,summary.path("cycleCount").asLong());assertTrue(summary.path("cyclesByState").isEmpty());
        assertTrue(summary.path("recentCycle").isNull());assertTrue(summary.path("lastActivityAt").isNull());
        assertTrue(checked("/api/systems/"+id+"/cycles",null,"AUTHOR",200).path("items").isEmpty());
        assertTrue(checked("/api/systems?q="+encoded(UUID.randomUUID().toString()),null,"AUTHOR",200).path("items").isEmpty());
        assertEquals("SYSTEM_NOT_FOUND",checked("/api/systems/"+UUID.randomUUID()+"/cycles",null,"AUTHOR",404).path("code").asString());
        checked("/api/systems/not-a-uuid/cycles",null,"AUTHOR",400);
    }

    /** Las colecciones nuevas documentan limites y errores estables sin exponer SQL. */
    @Test @DisplayName("Limites, tipos, estados y textos invalidos reciben errores HTTP controlados")
    void rejectsInvalidParameters()throws Exception{
        String id=system("nav-invalid-"+UUID.randomUUID()).path("systemId").asString();
        for(String path:List.of("/api/systems?limit=0","/api/systems?limit=101","/api/systems/"+id+"/cycles?limit=-1"))
            assertEquals("INVALID_LIMIT",checked(path,null,"AUTHOR",400).path("code").asString());
        checked("/api/systems?limit=not-a-number",null,"AUTHOR",400);
        assertEquals("INVALID_CURSOR",checked("/api/systems?after=broken",null,"AUTHOR",400).path("code").asString());
        assertEquals("INVALID_STATE",checked("/api/systems/"+id+"/cycles?state=invalid",null,"AUTHOR",400).path("code").asString());
        assertEquals("INVALID_FILTER",checked("/api/systems?q="+"a".repeat(256),null,"AUTHOR",400).path("code").asString());
        var failure=request("/api/systems?status="+"a".repeat(51),null,"AUTHOR");assertEquals(400,failure.statusCode());
        assertFalse(failure.body().contains("SELECT"));assertFalse(failure.body().contains("stackTrace"));
    }

    /** El cursor es opaco, permite cambiar el limite y rechaza filtros o sistemas distintos. */
    @Test @DisplayName("Navegacion HTTP reutiliza nextCursor conservando filtros y ambito")
    void paginatesOverHttp()throws Exception{
        String name="nav-pages-"+UUID.randomUUID();var a=system(name+"-a");var b=system(name+"-b");
        var first=checked("/api/systems?q="+encoded(name)+"&limit=1",null,"AUTHOR",200);String token=first.path("nextCursor").asString();
        var next=checked("/api/systems?q="+encoded(name.toUpperCase(Locale.ROOT))+"&limit=2&after="+encoded(token),null,"APPROVER",200);
        assertEquals(1,next.path("items").size());assertTrue(next.path("nextCursor").isNull());
        assertNotEquals(first.path("items").get(0).path("systemId"),next.path("items").get(0).path("systemId"));
        checked("/api/systems?q="+encoded(name)+"&status=ACTIVE&after="+encoded(token),null,"AUTHOR",400);
        String id=a.path("systemId").asString();cycle(id);cycle(id);
        var cycles=checked("/api/systems/"+id+"/cycles?limit=1",null,"AUTHOR",200);String cursor=cycles.path("nextCursor").asString();
        checked("/api/systems/"+id+"/cycles?state=E00_INACTIVO&after="+encoded(cursor),null,"AUTHOR",400);
        checked("/api/systems/"+b.path("systemId").asString()+"/cycles?after="+encoded(cursor),null,"AUTHOR",400);
    }

    /** La UI puede generar clientes desde la misma especificacion protegida que expone el servidor. */
    @Test @DisplayName("OpenAPI describe ambas colecciones, Basic y el limite maximo")
    void documentsNavigation()throws Exception{
        var document=checked("/v3/api-docs",null,"APPROVER",200);
        for(String path:List.of("/api/systems","/api/systems/{systemId}/cycles")){
            var operation=document.path("paths").path(path).path("get");assertFalse(operation.isMissingNode());
            assertTrue(operation.path("security").toString().contains("miecBasic"));
            var parameters=operation.path("parameters");JsonNode limit=null;
            for(var parameter:parameters)if(parameter.path("name").asString().equals("limit"))limit=parameter;
            assertNotNull(limit);assertEquals(1,limit.path("schema").path("minimum").asInt());assertEquals(100,limit.path("schema").path("maximum").asInt());
        }
    }
}
