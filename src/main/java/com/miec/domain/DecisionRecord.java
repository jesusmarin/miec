
package com.miec.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Registra una decisión tomada durante un ciclo MIEC.
 *
 * Conserva la decisión, su justificación, las evidencias
 * que la respaldan y la identificación de quien la registra.
 */
public final class DecisionRecord {

    private final UUID id;
    private final UUID cycleId;
    private final DecisionType decisionType;
    private final String outcome;
    private final String rationale;
    private final List<UUID> evidenceIds;
    private final String decidedBy;
    private final Instant recordedAt;

    public DecisionRecord(
            UUID cycleId,
            DecisionType decisionType,
            String outcome,
            String rationale,
            List<UUID> evidenceIds,
            String decidedBy
    ) {
        this.id = UUID.randomUUID();
        this.cycleId = Objects.requireNonNull(
                cycleId,
                "El identificador del ciclo es obligatorio."
        );
        this.decisionType = Objects.requireNonNull(
                decisionType,
                "El tipo de decisión es obligatorio."
        );
        this.outcome = requireText(
                outcome,
                "El resultado de la decisión es obligatorio."
        );
        this.rationale = requireText(
                rationale,
                "La justificación de la decisión es obligatoria."
        );
        this.evidenceIds = validateEvidenceIds(evidenceIds);
        this.decidedBy = requireText(
                decidedBy,
                "El responsable de registrar la decisión es obligatorio."
        );
        this.recordedAt = Instant.now();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }

    private static List<UUID> validateEvidenceIds(List<UUID> evidenceIds) {
        Objects.requireNonNull(
                evidenceIds,
                "La lista de evidencias es obligatoria."
        );

        if (evidenceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "La lista no puede contener identificadores de evidencia nulos."
            );
        }

        return List.copyOf(evidenceIds);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public DecisionType getDecisionType() {
        return decisionType;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getRationale() {
        return rationale;
    }

    public List<UUID> getEvidenceIds() {
        return evidenceIds;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public boolean hasEvidence() {
        return !evidenceIds.isEmpty();
    }
}