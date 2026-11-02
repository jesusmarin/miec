package com.miec.action.integration;

import com.miec.action.IdempotentActionExecutor;
import java.util.Objects;
import java.util.UUID;

/** Fuente de solo consulta autenticada, instalada por configuración confiable del servidor. */
public interface DestinationEvidenceSource {
    /** Identidad estable del destinatario; no es una URL proporcionada por el solicitante. */
    String destinationId();

    /**
     * Consulta sin efectos usando el snapshot persistido. La implementación autentica
     * el destinatario, valida su respuesta y aplica límites de tiempo al transporte.
     */
    Observation lookup(IdempotentActionExecutor.Context context);

    /** Resultado ligado explícitamente a la identidad y contenido de la operación consultada. */
    record Observation(UUID operationKey, String destinationId, String fingerprint,
                       IdempotentActionExecutor.Status status, String resultReference) {
        /** Exige identidad y una referencia útil exclusivamente para resultados aplicados. */
        public Observation {
            Objects.requireNonNull(operationKey);
            Objects.requireNonNull(destinationId);
            Objects.requireNonNull(fingerprint);
            Objects.requireNonNull(status);
            if (status == IdempotentActionExecutor.Status.APPLIED
                    && (resultReference == null || resultReference.isBlank() || resultReference.length() > 500)) {
                throw new IllegalArgumentException("Referencia aplicada inválida");
            }
            if (status != IdempotentActionExecutor.Status.APPLIED && resultReference != null) {
                throw new IllegalArgumentException("Un resultado incierto no acredita una referencia");
            }
        }
    }
}
