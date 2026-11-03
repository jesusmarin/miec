package com.miec.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Consola opcional de desarrollo: requiere perfil dev, activación explícita, loopback y AUTHOR. */
@Configuration(proxyBeanMethods=false)
@Profile("dev")
@ConditionalOnProperty(name="spring.h2.console.enabled",havingValue="true")
public class DevelopmentConsoleSecurityConfiguration {
    /** Aísla las excepciones de frames y CSRF de la consola; ninguna alcanza a la API. */
    @Bean
    @Order(1)
    SecurityFilterChain developmentConsole(HttpSecurity http,
            @Value("${spring.h2.console.path:/h2-console}") String path) throws Exception {
        http.securityMatcher(path,path+"/**")
                .authorizeHttpRequests(rules->rules.anyRequest().access((authentication,context)-> {
                    String address=context.getRequest().getRemoteAddr();
                    boolean local="127.0.0.1".equals(address)||"::1".equals(address)||"0:0:0:0:0:0:0:1".equals(address);
                    boolean author=authentication.get().getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_AUTHOR"));
                    return new AuthorizationDecision(local&&author);
                }))
                .httpBasic(basic->{})
                .sessionManagement(sessions->sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache->cache.disable()).csrf(csrf->csrf.disable())
                .formLogin(form->form.disable()).logout(logout->logout.disable())
                .headers(headers->headers.frameOptions(frame->frame.sameOrigin()))
                .exceptionHandling(errors->errors.accessDeniedHandler((request,response,failure)->response.sendError(HttpServletResponse.SC_FORBIDDEN)));
        return http.build();
    }
}
