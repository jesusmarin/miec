package com.miec.orchestration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Comprueba que invocar la fachada sin HTTP tampoco permite eludir identidad o roles. */
class LifecycleApplicationServiceTest {
    private final LifecycleService persistence=mock(LifecycleService.class);
    private final LifecycleApplicationService service=new LifecycleApplicationService(persistence,new InterventionIdentity());

    /** Evita propagar la identidad de una prueba hacia otra. */
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    /** Instala una identidad que representa un Authentication del servidor, no un campo del cliente. */
    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "server-author","unused",List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }

    /** Ningún caso de uso accede a persistencia sin autenticación. */
    @Test void shouldRejectUnauthenticatedFacadeCalls() {
        UUID id=UUID.randomUUID();
        assertEquals("AUTHENTICATION_REQUIRED",assertThrows(LifecycleException.class,()->service.getSystem(id)).code());
        assertThrows(LifecycleException.class,()->service.getCycle(id));
        assertThrows(LifecycleException.class,()->service.history(id,50,null));
        assertThrows(LifecycleException.class,()->service.createSystem(new LifecycleCommands.CreateSystem("s",null,null,null)));
        assertThrows(LifecycleException.class,()->service.createCycle(id,new LifecycleCommands.CreateCycle(null)));
        assertThrows(LifecycleException.class,()->service.startCycle(id,new LifecycleCommands.StartCycle(0L,"inicio")));
        verifyNoInteractions(persistence);
    }

    /** APPROVER consulta pero no crea sistemas, ciclos ni inicios. */
    @Test void shouldSeparateReaderAndAuthorPermissions() {
        authenticate("APPROVER"); UUID id=UUID.randomUUID();
        service.getSystem(id); service.getCycle(id); service.history(id,50,null);
        verify(persistence).getSystem(id); verify(persistence).getCycle(id); verify(persistence).history(id,50,null);
        assertThrows(LifecycleException.class,()->service.createSystem(new LifecycleCommands.CreateSystem("s",null,null,null)));
        assertThrows(LifecycleException.class,()->service.createCycle(id,new LifecycleCommands.CreateCycle(null)));
        assertThrows(LifecycleException.class,()->service.startCycle(id,new LifecycleCommands.StartCycle(0L,"inicio")));
        verifyNoMoreInteractions(persistence);
    }

    /** La auditoría del ciclo recibe el principal autenticado y no una identidad aportada por el comando. */
    @Test void shouldPropagateServerIdentityToPersistence() {
        authenticate("AUTHOR"); UUID id=UUID.randomUUID();
        var create=new LifecycleCommands.CreateCycle(null);
        var start=new LifecycleCommands.StartCycle(0L,"inicio");
        service.createCycle(id,create); service.startCycle(id,start);
        verify(persistence).createCycle(id,create,"server-author");
        verify(persistence).startCycle(id,start,"server-author");
    }
}
