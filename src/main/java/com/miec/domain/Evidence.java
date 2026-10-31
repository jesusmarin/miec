package com.miec.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Representa una evidencia recopilada durante un ciclo MIEC.
 *
 * La evidencia aporta información verificable para respaldar
 * observaciones, diagnósticos, ejecuciones y decisiones.
 */
public final class Evidence {

    private final UUID id;
    private final UUID cycleId;
    private final EvidenceType evidenceType;
    private final String source;
    private final String description;
    private final String content;
    private final Instant collectedAt;
    private final String collectedBy;

    public Evidence(
            UUID cycleId,
            EvidenceType evidenceType,
            String source,
            String description,
            String content,
            String collectedBy
    ) {
        this.id = UUID.randomUUID();
        this.cycleId = requireNotNull(cycleId, "El identificador del ciclo es obligatorio.");
        this.evidenceType = requireNotNull(evidenceType, "El tipo de evidencia es obligatorio.");
        this.source = requireText(source, "El origen de la evidencia es obligatorio.");
        this.description = requireText(description, "La descripción de la evidencia es obligatoria.");
        this.content = requireText(content, "El contenido de la evidencia es obligatorio.");
        this.collectedBy = requireText(collectedBy, "El responsable de recopilación es obligatorio.");
        this.collectedAt = Instant.now();
    }

    private static <T> T requireNotNull(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public String getSource() {
        return source;
    }

    public String getDescription() {
        return description;
    }

    public String getContent() {
        return content;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public String getCollectedBy() {
        return collectedBy;
    }
}