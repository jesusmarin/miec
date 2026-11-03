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
    record Context(ExecutionOperationRegistry.Operation operation,ExecutionActivityService.Ownership ownership,
                   ExecutionRequest request) {
        /** Exige que los bytes entregados correspondan a la clave y huella persistidas. */
        public Context {
            java.util.Objects.requireNonNull(operation);java.util.Objects.requireNonNull(ownership);
            java.util.Objects.requireNonNull(request);
            if(!request.destinationId().equals(operation.destination())||!request.fingerprint().equals(operation.fingerprint())) {
                throw new IllegalArgumentException("Solicitud distinta de la operación registrada");
            }
        }
    }
    /** Captura una única solicitud inmutable antes de reservar la clave y producir efectos. */
    ExecutionRequest request();
    /** Consulta durablemente si la operación ya ocurrió, sin ejecutar el efecto. */
    Lookup lookup(Context context);
    /** Guarda local sin IO ni efectos; el núcleo revalida permiso y propiedad después de este hook. */
    default void beforeSend(Context context) { }
    /** Envía usando la clave; repetirla debe devolver el mismo resultado sin duplicar el efecto. */
    String execute(Context context);
}
