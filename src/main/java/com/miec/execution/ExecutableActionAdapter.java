package com.miec.execution;

import com.miec.action.ExecutionRequest;
import com.miec.action.IdempotentActionExecutor;

/** Puerto instalado por el servidor; sus implementaciones deben cumplir deduplicación durable del destinatario. */
public interface ExecutableActionAdapter {
    /** Publica identidad y huella no secreta de la configuración que interpreta solicitudes aprobadas. */
    ExecutableActions.Descriptor descriptor();

    /**
     * Valida y captura sin realizar IO ni efectos. El ejecutor devuelto debe conservar
     * exactamente request; el catálogo no acredita el cumplimiento remoto del contrato.
     */
    IdempotentActionExecutor bind(ExecutionRequest request);
}
