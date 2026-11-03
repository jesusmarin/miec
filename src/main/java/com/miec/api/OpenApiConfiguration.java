package com.miec.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentación del contrato autenticado sin secretos ni permisos de ejecución implícitos. */
@Configuration(proxyBeanMethods=false)
public class OpenApiConfiguration {
    /** Declara Basic solo como contrato; la protección real reside en Spring Security. */
    @Bean OpenAPI mvpOpenApi() {
        return new OpenAPI().info(new Info().title("MIEC Prototype - MVP v01").version("0.1")
                .description("MVP local autenticado. AUTHOR registra e inicia; APPROVER decide permisos; ambos consultan. X-MIEC-Actor se ignora. El despliegue remoto requiere TLS y controles adicionales."))
                .components(new Components().addSecuritySchemes("miecBasic",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }
}
