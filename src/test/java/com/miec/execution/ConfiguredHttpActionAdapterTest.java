package com.miec.execution;

import com.miec.action.*;
import com.miec.integration.contract.HttpRecipientFixture;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Transporte real y límites del adaptador genérico; no depende de PetClinic ni de sus DTO. */
class ConfiguredHttpActionAdapterTest {
    private HttpRecipientFixture recipient;
    private final JsonMapper json=JsonMapper.builder().build();
    /** Crea un servidor y un ledger JDBC independientes por prueba. */
    @BeforeEach void start() throws Exception { recipient=new HttpRecipientFixture("jdbc:h2:mem:http_adapter_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"); }
    /** Libera servidor, hilos y base propios incluso si falla la prueba. */
    @AfterEach void close() { recipient.close(); }
    /** Configura únicamente enlaces de servidor; el cuerpo no incluye rutas ni secretos. */
    private ConfiguredHttpActionAdapter adapter(Duration timeout) {
        return new ConfiguredHttpActionAdapter(json,"http-test","1","recipient",recipient.base(),HttpRecipientFixture.API_KEY,"/miec/operations",timeout);
    }
    /** Construye una identidad de intento para probar transporte sin invocar el coordinador de aplicación. */
    private IdempotentActionExecutor.Context context(ExecutionRequest request) {
        var operation=new ExecutionOperationRegistry.Operation(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),request.destinationId(),request.fingerprint(),request);
        return new IdempotentActionExecutor.Context(operation,new ExecutionActivityService.Ownership(UUID.randomUUID(),"test-owner",1),request);
    }

    /** La consulta es sin efecto y replay con misma clave/contenido devuelve la referencia sin duplicación. */
    @Test void shouldLookupExecuteAndReplayExactRequest() {
        var request=new ExecutionRequest("recipient","application/json","{ \"value\": 1 }");var bound=adapter(Duration.ofSeconds(3)).bind(request);var context=context(request);
        assertEquals(IdempotentActionExecutor.Status.NOT_FOUND,bound.lookup(context).status());assertEquals(0,recipient.effects());
        String reference=bound.execute(context);assertEquals(reference,bound.execute(context));
        assertEquals(reference,bound.lookup(context).resultReference());assertEquals(1,recipient.effects());
    }

    /** Perder la respuesta tras commit no demuestra ausencia; la consulta posterior recupera la referencia. */
    @Test void shouldPreserveEffectAfterLostResponseAndRejectWrongIdentity() {
        var request=new ExecutionRequest("recipient","application/json","{}");var bound=adapter(Duration.ofSeconds(2)).bind(request);var context=context(request);
        recipient.mode("CLOSE_AFTER_COMMIT");assertThrows(IllegalStateException.class,()->bound.execute(context));assertEquals(1,recipient.effects());
        recipient.mode("NORMAL");assertEquals(IdempotentActionExecutor.Status.APPLIED,bound.lookup(context).status());
        recipient.mode("WRONG_IDENTITY");assertEquals(IdempotentActionExecutor.Status.UNKNOWN,bound.lookup(context).status());
    }

    /** Ausencias ajenas al contrato, redirecciones y cuerpos demasiado grandes nunca habilitan un envío. */
    @Test void shouldTreatUnexpectedResponsesAsUnknown() {
        var request=new ExecutionRequest("recipient","application/json","{}");var bound=adapter(Duration.ofSeconds(2)).bind(request);var context=context(request);
        for(String mode:new String[]{"GENERIC_404","REDIRECT","OVERSIZED"}) {
            recipient.mode(mode);assertEquals(IdempotentActionExecutor.Status.UNKNOWN,bound.lookup(context).status(),mode);
        }
        assertEquals(0,recipient.effects());
    }

    /** El plazo también cubre cuerpos que no terminan de llegar tras enviar sus cabeceras. */
    @Test void shouldBoundWholeResponseTime() {
        var request=new ExecutionRequest("recipient","application/json","{}");var bound=adapter(Duration.ofMillis(250)).bind(request);
        recipient.mode("SLOW_BODY");
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertEquals(IdempotentActionExecutor.Status.UNKNOWN,bound.lookup(context(request)).status()));
    }

    /** URLs inseguras, cuerpos incompatibles y cambios de ruta se rechazan sin exponer secretos. */
    @Test void shouldValidateConfigurationAndBindRoutingWithoutSecret() {
        assertThrows(IllegalArgumentException.class,()->new ConfiguredHttpActionAdapter(json,"a","1","recipient",URI.create("http://example.com"),HttpRecipientFixture.API_KEY,"/miec/operations",Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->adapter(Duration.ofSeconds(31)));
        assertThrows(IllegalArgumentException.class,()->adapter(Duration.ofSeconds(1)).bind(new ExecutionRequest("recipient","application/json","[]")));
        var first=adapter(Duration.ofSeconds(1));
        var rotated=new ConfiguredHttpActionAdapter(json,"http-test","1","recipient",recipient.base(),"different-rotated-test-secret","/miec/operations",Duration.ofSeconds(2));
        assertEquals(first.descriptor().bindingFingerprint(),rotated.descriptor().bindingFingerprint());
        var rerouted=new ConfiguredHttpActionAdapter(json,"http-test","1","recipient",recipient.base(),HttpRecipientFixture.API_KEY,"/another/operation",Duration.ofSeconds(1));
        assertNotEquals(first.descriptor().bindingFingerprint(),rerouted.descriptor().bindingFingerprint());
    }
}
