
package com.miec.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EvidenceAssessment(
        EvidenceSufficiency sufficiency,
        List<UUID> evidenceIds,
        List<String> limitations,
        List<String> unresolvedUncertainties,
        String rationale,
        Instant assessedAt
) {

    public EvidenceAssessment {
        if (sufficiency == null) {
            throw new IllegalArgumentException(
                    "Evidence sufficiency is required");
        }

        evidenceIds = validateEvidenceIds(evidenceIds);
        limitations = validateTextList(limitations, "Limitations");
        unresolvedUncertainties = validateTextList(
                unresolvedUncertainties,
                "Unresolved uncertainties"
        );

        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException(
                    "Assessment rationale is required");
        }

        rationale = rationale.trim();

        if (assessedAt == null) {
            throw new IllegalArgumentException(
                    "Assessment timestamp is required");
        }
    }

    private static List<UUID> validateEvidenceIds(List<UUID> ids) {
        Objects.requireNonNull(ids, "Evidence IDs are required");

        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Evidence IDs cannot contain null elements");
        }

        return List.copyOf(ids);
    }

    private static List<String> validateTextList(
            List<String> values,
            String fieldName) {

        Objects.requireNonNull(values, fieldName + " are required");

        if (values.stream().anyMatch(
                value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(
                    fieldName + " cannot contain null or blank elements");
        }

        return values.stream()
                .map(String::trim)
                .toList();
    }

    public boolean allowsEvaluation() {
        return sufficiency.allowsEvaluation();
    }
}

