package com.miec.orchestration;

/** Rechazo de aplicación independiente de HTTP y sin datos de infraestructura. */
public final class LifecycleException extends RuntimeException {
    /** Clasificación que el adaptador REST transforma en un estado HTTP. */
    public enum Kind { BAD_REQUEST, NOT_FOUND, CONFLICT }

    private final Kind kind;
    private final String code;

    /** Conserva un código estable y un mensaje seguro para el consumidor. */
    public LifecycleException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    /** Clasificación del rechazo. */
    public Kind kind() { return kind; }

    /** Código independiente del texto del mensaje. */
    public String code() { return code; }
}
