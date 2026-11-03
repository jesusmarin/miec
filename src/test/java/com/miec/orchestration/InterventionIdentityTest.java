package com.miec.orchestration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Permisos de la fachada incluso cuando se invoca sin un controlador HTTP. */
class InterventionIdentityTest {
    private final InterventionIdentity identity=new InterventionIdentity();
    /** Evita que una identidad de prueba se propague a otra prueba o contexto. */
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    /** Un contexto vacío o anónimo no se transforma en un operador por tener un nombre. */
    @Test void shouldRejectMissingAndAnonymousIdentity() {
        SecurityContextHolder.clearContext();
        assertEquals(LifecycleException.Kind.UNAUTHORIZED,assertThrows(LifecycleException.class,identity::reader).kind());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test","anonymous",List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThrows(LifecycleException.class,identity::author);
    }
    /** El autor puede leer y proponer, pero no decidir. */
    @Test void shouldSeparateAuthorRole() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("author","test",List.of(new SimpleGrantedAuthority("ROLE_AUTHOR"))));
        assertEquals("author",identity.author()); assertEquals("author",identity.reader());
        assertEquals(LifecycleException.Kind.FORBIDDEN,assertThrows(LifecycleException.class,identity::approver).kind());
    }
    /** El aprobador puede leer y decidir, pero no proponer como autor. */
    @Test void shouldSeparateApproverRole() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("reviewer","test",List.of(new SimpleGrantedAuthority("ROLE_APPROVER"))));
        assertEquals("reviewer",identity.approver()); assertEquals("reviewer",identity.reader());
        assertThrows(LifecycleException.class,identity::author);
    }
}
