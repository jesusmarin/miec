package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Prueba huellas calculadas sobre el contenido real y defensa frente a mutación de bytes. */
class ExecutionRequestTest {
    /** Mismos datos generan la misma huella; ningún cambio de contenido o destino se confunde. */
    @Test @DisplayName("Huella estable del contenido UTF-8 exacto y metadatos normalizados")
    void shouldHashExactImmutableContent() {
        var request=new ExecutionRequest(" destination ","APPLICATION/JSON","{\"x\":1}");
        assertEquals(request.fingerprint(),new ExecutionRequest("destination","application/json","{\"x\":1}").fingerprint());
        assertNotEquals(request.fingerprint(),new ExecutionRequest("destination","application/json","{ \"x\":1}").fingerprint());
        assertNotEquals(request.fingerprint(),new ExecutionRequest("other","application/json",request.body()).fingerprint());
        String original=request.fingerprint();var bytes=request.bodyBytes();bytes[0]=0;
        assertEquals(original,request.fingerprint());assertEquals('{',request.bodyBytes()[0]);
        assertThrows(IllegalArgumentException.class,()->new ExecutionRequest("destination","text/plain","a".repeat(1_048_577)));
    }
}
