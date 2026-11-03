package com.miec.orchestration;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Set;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Identidad del servidor; no deserializa roles ni nombres aportados en comandos HTTP. */
@Component
class InterventionIdentity {
    /** Exige autor para registrar contexto, planes o solicitudes. */
    String author() { return require(Set.of("ROLE_AUTHOR")); }
    /** Exige aprobador para decidir solicitudes. */
    String approver() { return require(Set.of("ROLE_APPROVER")); }
    /** Permite consultar a ambos roles instalados. */
    String reader() { return require(Set.of("ROLE_AUTHOR","ROLE_APPROVER")); }

    /** Usa exclusivamente el Authentication establecido por Spring Security. */
    private String require(Set<String> roles) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated()||authentication instanceof AnonymousAuthenticationToken) {
            throw fail(UNAUTHORIZED,"AUTHENTICATION_REQUIRED","Se requiere autenticación");
        }
        if(authentication.getAuthorities().stream().noneMatch(authority->roles.contains(authority.getAuthority()))) {
            throw fail(FORBIDDEN,"FORBIDDEN","El rol no permite esta operación");
        }
        return authentication.getName();
    }
}
