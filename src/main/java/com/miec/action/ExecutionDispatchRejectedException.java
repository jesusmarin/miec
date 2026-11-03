package com.miec.action;

import java.util.UUID;

/** Rechazo saneado de la admisión local; no equivale a ausencia global de efecto de la operación. */
public final class ExecutionDispatchRejectedException extends AuthorizedActionExecutionService.ExecutionDeniedException {
    /** Códigos cerrados, independientes de mensajes JDBC, contenido o credenciales. */
    public enum Code {
        AUTHORIZATION_INVALID, APPROVED_CONTENT_CHANGED, REQUEST_CHANGED, ATTEMPT_CONTEXT_CHANGED,
        ATTEMPT_NOT_RUNNING, ATTEMPT_OWNER_CHANGED, ATTEMPT_LEASE_EXPIRED,
        ATTEMPT_HEARTBEAT_FAILED, THREAD_INTERRUPTED, PRE_SEND_CONTEXT_REJECTED, ADMISSION_UNAVAILABLE
    }
    private final Code code;
    private final UUID executionId;

    /** Solo el núcleo construye rechazos; conserva la causa para auditoría interna, nunca para el DTO HTTP. */
    ExecutionDispatchRejectedException(Code code,UUID executionId,Throwable cause) {
        super("IR-009: envío no admitido: "+code.name(),cause); this.code=code; this.executionId=executionId;
    }
    /** Código estable que no contiene datos recibidos del adaptador. */
    public Code code() { return code; }
    /** Intento al que corresponde este rechazo anterior al envío. */
    public UUID executionId() { return executionId; }
}
