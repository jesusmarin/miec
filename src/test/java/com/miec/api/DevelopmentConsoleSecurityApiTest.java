package com.miec.api;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

/** La excepción de consola se limita al perfil dev explícito y no elimina los permisos de la API. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:mvp_dev_console;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "spring.h2.console.enabled=true","miec.api.author.username=author","miec.api.author.password=author-dev-console-test",
        "miec.api.approver.username=approver","miec.api.approver.password=approver-dev-console-test"})
class DevelopmentConsoleSecurityApiTest {
    @LocalServerPort int port;
    @Autowired ServletContext servletContext;

    /** Envía solicitudes locales sin guardar sesión ni seguir redirecciones automáticamente. */
    private HttpResponse<String> get(String path,String user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).GET();
        if(user!=null) request.header("Authorization","Basic "+Base64.getEncoder().encodeToString(
                (user+":"+(user.equals("author")?"author-dev-console-test":"approver-dev-console-test")).getBytes(StandardCharsets.UTF_8)));
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    /** Solo AUTHOR local accede al servlet; anónimos y aprobadores permanecen bloqueados. */
    @Test void shouldRequireAuthorForExplicitDevelopmentConsole() throws Exception {
        assertTrue(servletContext.getServletRegistrations().values().stream().anyMatch(r->r.getClassName().contains("h2.server.web")));
        assertEquals(401,get("/h2-console/",null).statusCode());
        assertEquals(403,get("/h2-console/","approver").statusCode());
        var author=get("/h2-console/","author");
        assertTrue(author.statusCode()==200||author.statusCode()==302,author.body());
        assertEquals("SAMEORIGIN",author.headers().firstValue("X-Frame-Options").orElseThrow());
        assertEquals(401,get("/api/systems/00000000-0000-0000-0000-000000000000",null).statusCode());
        assertEquals("DENY",get("/v3/api-docs","author").headers().firstValue("X-Frame-Options").orElseThrow());
    }
}
