package com.miec.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentación sin secretos y sin seguridad global ficticia para las rutas históricas del MVP. */
@Configuration(proxyBeanMethods=false)
public class OpenApiConfiguration {
    /** Declara Basic solo como contrato; la protección real reside en Spring Security. */
    @Bean OpenAPI mvpOpenApi() {
        return new OpenAPI().info(new Info().title("MIEC Prototype - MVP v01").version("0.1")
                .description("Uso local. Objetivos medibles, observaciones y decisiones humanas. Las rutas históricas de MVP-01 conservan su contrato local. El despliegue remoto requiere TLS."))
                .components(new Components().addSecuritySchemes("miecBasic",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }
}
