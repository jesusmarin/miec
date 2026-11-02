package com.miec.action;

/**
 * Adaptador interno para un destinatario con deduplicación durable. El destino
 * debe aplicar atómicamente la clave y el efecto, rechazar cambios de contenido
 * y conservar el resultado durante toda la vida de los reintentos. MIEC no puede
 * imponer ese comportamiento a un sistema externo solo con esta interfaz.
 */
public interface IdempotentActionExecutor {
    /** Resultado de consultar la clave en el destinatario. UNKNOWN nunca autoriza un envío. */
    enum Status { APPLIED, NOT_FOUND, UNKNOWN }
    /** Respuesta de consulta y referencia durable cuando el efecto está aplicado. */
    record Lookup(Status status,String resultReference) {
        /** Valida el estado y la referencia de un resultado aplicado. */
        public Lookup {
            java.util.Objects.requireNonNull(status);
            if(status==Status.APPLIED) resultReference=ExecutionRecoveryAccess.text(resultReference,500);
        }
    }
    /** Contexto enviado al destino: misma clave entre intentos y generación actual. */
    record Context(ExecutionOperationRegistry.Operation operation,ExecutionActivityService.Ownership ownership) { }
    /** Identidad estable del destinatario configurado. */
    String destinationId();
    /** SHA-256 hexadecimal del contenido canónico realmente enviado. */
    String requestFingerprint();
    /** Consulta durablemente si la operación ya ocurrió, sin ejecutar el efecto. */
    Lookup lookup(Context context);
    /** Envía usando la clave; repetirla debe devolver el mismo resultado sin duplicar el efecto. */
    String execute(Context context);
}
