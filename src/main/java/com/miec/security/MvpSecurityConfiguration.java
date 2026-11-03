package com.miec.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Seguridad uniforme del MVP; sin claves incorporadas ni confianza en cabeceras de actor. */
@Configuration(proxyBeanMethods=false)
public class MvpSecurityConfiguration {
    /** Usa el codificador estándar; las claves recibidas por configuración se conservan como hashes en el manager. */
    @Bean PasswordEncoder mvpPasswordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    /** Instala solo cuentas configuradas; con claves vacías las rutas protegidas permanecen inaccesibles. */
    @Bean InMemoryUserDetailsManager mvpUsers(PasswordEncoder encoder,
            @Value("${miec.api.author.username:author}") String author,
            @Value("${miec.api.author.password:}") String authorPassword,
            @Value("${miec.api.approver.username:approver}") String approver,
            @Value("${miec.api.approver.password:}") String approverPassword) {
        if(!authorPassword.isEmpty()&&!approverPassword.isEmpty()&&(author.equalsIgnoreCase(approver)||authorPassword.equals(approverPassword))) {
            throw new IllegalArgumentException("Autor y aprobador requieren identidades y credenciales diferentes");
        }
        var users=new InMemoryUserDetailsManager();
        add(users,encoder,author,authorPassword,"AUTHOR");
        add(users,encoder,approver,approverPassword,"APPROVER");
        return users;
    }

    /** Valida configuración sin imprimir secretos y omite una cuenta deshabilitada por clave vacía. */
    private static void add(InMemoryUserDetailsManager users,PasswordEncoder encoder,String name,String password,String role) {
        if(password.isEmpty()) return;
        if(!name.matches("[A-Za-z0-9._-]{1,100}")||password.isBlank()||password.length()<12) {
            throw new IllegalArgumentException("Configuración de usuario inválida; la clave requiere al menos 12 caracteres");
        }
        users.createUser(User.withUsername(name).password(encoder.encode(password)).roles(role).build());
    }

    /** Protege toda la aplicación por defecto; únicamente los métodos y rutas previstos reciben permisos. */
    @Bean SecurityFilterChain interventionSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(rules->rules
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/**","/swagger-ui/**","/swagger-ui.html","/v3/api-docs","/v3/api-docs/**","/v3/api-docs.yaml").hasAnyRole("AUTHOR","APPROVER")
                        .requestMatchers(HttpMethod.POST,"/api/authorizations/*/approve","/api/authorizations/*/reject",
                                "/api/cycles/*/plans/*/measurements/reviews","/api/cycles/*/plans/*/measurements/seal",
                                "/api/plans/*/execution/recover","/api/plans/*/execution/finish","/api/plans/*/execution/reconcile",
                                "/api/execution/reconciliation-authorizations/*/approve","/api/execution/reconciliation-authorizations/*/reject").hasRole("APPROVER")
                        .requestMatchers(HttpMethod.POST,"/api/**").hasRole("AUTHOR")
                        .anyRequest().denyAll())
                .httpBasic(basic->basic.authenticationEntryPoint((request,response,failure)->error(response,401,"AUTHENTICATION_REQUIRED")))
                .sessionManagement(sessions->sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache->cache.disable()).csrf(csrf->csrf.ignoringRequestMatchers("/api/**"))
                .formLogin(form->form.disable()).logout(logout->logout.disable())
                .exceptionHandling(errors->errors
                        .authenticationEntryPoint((request,response,failure)->error(response,401,"AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request,response,failure)->error(response,403,"FORBIDDEN")));
        return http.build();
    }

    /** Responde sin redirecciones, datos de credenciales ni detalles de autenticación. */
    private static void error(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        if(status==401) response.setHeader("WWW-Authenticate","Basic realm=\"MIEC MVP\"");
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\"Acceso no permitido\",\"fields\":{}}");
    }
}
