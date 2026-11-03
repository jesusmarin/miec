package com.miec.action;

import java.util.UUID;

/** Auditoría no confirmada: conserva el fallo original y expone únicamente una correlación saneada por HTTP. */
public final class ExecutionIncidentAuditException extends RuntimeException {
    private final UUID incidentId;
    /** No sustituye ni descarta las causas y excepciones suprimidas del fallo de ejecución. */
    public ExecutionIncidentAuditException(UUID incidentId,Throwable original) {
        super("No se pudo confirmar la auditoría de ejecución",original);this.incidentId=java.util.Objects.requireNonNull(incidentId);
    }
    /** Correlación que permite localizar el log aunque la base de datos no estuviera disponible. */
    public UUID incidentId() { return incidentId; }
}
