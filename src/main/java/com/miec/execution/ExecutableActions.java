package com.miec.execution;

import com.miec.action.ExecutionRequest;
import jakarta.validation.constraints.*;

/** Contrato ejecutable versionado; no acepta URLs, comandos de sistema ni credenciales del cliente. */
public final class ExecutableActions {
    /** Contenedor de valores no instanciable. */
    private ExecutableActions() { }

    /**
     * Entrada anterior a aprobación. body conserva sus bytes UTF-8; reformatear JSON
     * constituye una solicitud distinta. Los límites en bytes se verifican en el catálogo.
     */
    public record Spec(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,100}") String adapterId,
                       @NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,100}") String adapterVersion,
                       @NotBlank @Size(max=255) String destinationId,
                       @NotBlank @Size(max=255) String mediaType,
                       @NotNull @Size(max=65536) String body) { }

    /** Identidad pública del enlace instalado; bindingFingerprint excluye secretos y fija ruta/protocolo. */
    public record Descriptor(String adapterId,String adapterVersion,String destinationId,String bindingFingerprint) { }

    /**
     * Snapshot aprobado, generado por el servidor. Incluido dentro del JSON de acciones
     * y por ello en la huella del plan. Una rotación de secretos no altera su contenido.
     */
    public record Definition(String schemaVersion,String adapterId,String adapterVersion,String destinationId,
                             String bindingFingerprint,String mediaType,String body) {
        /** Reconstruye exactamente la solicitud durable que recibirá IR-009. */
        public ExecutionRequest request() { return new ExecutionRequest(destinationId,mediaType,body); }
    }
}
