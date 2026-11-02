package com.miec.action.integration;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Registro inmutable de fuentes instaladas como beans; no admite registro desde una solicitud. */
@Component
public final class DestinationEvidenceSources {
    private final Map<String, DestinationEvidenceSource> sources;

    /** Rechaza configuración ambigua; una instalación sin adaptadores queda cerrada por defecto. */
    public DestinationEvidenceSources(List<DestinationEvidenceSource> configuredSources) {
        var registered = new HashMap<String, DestinationEvidenceSource>();
        for (var source : List.copyOf(configuredSources)) {
            String destination = Objects.requireNonNull(source.destinationId());
            if (destination.isBlank() || destination.length() > 255 || !destination.equals(destination.trim())) {
                throw new IllegalArgumentException("Destino de evidencia inválido");
            }
            if (registered.putIfAbsent(destination, source) != null) {
                throw new IllegalArgumentException("Fuentes de evidencia duplicadas para el destino");
            }
        }
        sources = Map.copyOf(registered);
    }

    /** Selecciona exclusivamente la fuente configurada para el destinatario persistido. */
    public DestinationEvidenceSource require(String destinationId) {
        var source = sources.get(Objects.requireNonNull(destinationId));
        if (source == null) throw new IllegalStateException("Destino sin fuente de evidencia configurada");
        return source;
    }
}
