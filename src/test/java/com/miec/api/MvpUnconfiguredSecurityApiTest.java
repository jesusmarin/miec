package com.miec.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Ninguna cuenta implícita permite usar el servidor sin claves configuradas. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:mvp_no_accounts;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "miec.api.author.password=","miec.api.approver.password="})
class MvpUnconfiguredSecurityApiTest {
    @LocalServerPort int port;

    /** Las rutas históricas y nuevas siguen cerradas aun declarando un actor o intentando la cuenta por defecto. */
    @Test void shouldKeepApiClosedWhenAccountsAreDisabled() throws Exception {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for(String path:new String[]{"/api/systems","/api/cycles/00000000-0000-0000-0000-000000000000/measurement","/v3/api-docs"}) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
                    .header("X-MIEC-Actor","author").header("Authorization","Basic YXV0aG9yOg==").GET().build();
            assertEquals(401,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode(),path);
        }
    }
}
