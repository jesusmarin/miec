package com.miec.orchestration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Comandos del primer flujo del MVP; los estados, fechas y números se asignan en el servidor. */
public final class LifecycleCommands {
    /** Evita instancias del contenedor de comandos. */
    private LifecycleCommands() { }

    /** Datos mínimos para registrar un sistema, sin conceder autorizaciones de ejecución. */
    public record CreateSystem(@NotBlank @Size(max=255) String name,
                               @Size(max=100) String domain,
                               @Size(max=4000) String description,
                               @Size(max=255) String ownerReference) {
        /** Normaliza texto sin sustituir la validación del comando. */
        public CreateSystem {
            name = name == null ? null : name.trim();
            domain = optional(domain);
            description = optional(description);
            ownerReference = optional(ownerReference);
        }
    }

    /** Un ciclo empieza inactivo y A0; no admite estado ni autonomía elegidos por el cliente. */
    public record CreateCycle(@Size(max=100) String configurationVersionId) {
        /** Conserva una referencia de configuración opcional, sin activar políticas distintas. */
        public CreateCycle { configurationVersionId = optional(configurationVersionId); }
    }

    /** Inicio explícito con control de versión y motivo auditable. */
    public record StartCycle(@NotNull @PositiveOrZero Long expectedVersion,
                             @NotBlank @Size(max=500) String reason) {
        /** Normaliza el motivo; la versión es obligatoria incluso cuando vale cero. */
        public StartCycle { reason = reason == null ? null : reason.trim(); }
    }

    /** Convierte texto opcional vacío en ausencia. */
    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
