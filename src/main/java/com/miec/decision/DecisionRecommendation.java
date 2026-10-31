
package com.miec.decision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DecisionRecommendation(
        DecisionAction action,
        String rationale,
        List<UUID> supportingEvidenceIds,
        Instant recommendedAt
) {

    public DecisionRecommendation {
        Objects.requireNonNull(action, "Action is required");

        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException(
                    "Recommendation rationale is required");
        }

        rationale = rationale.trim();

        Objects.requireNonNull(
                supportingEvidenceIds,
                "Supporting evidence IDs are required");

        if (supportingEvidenceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Supporting evidence IDs cannot contain null elements");
        }

        supportingEvidenceIds = List.copyOf(supportingEvidenceIds);

        Objects.requireNonNull(
                recommendedAt,
                "Recommendation timestamp is required");
    }
}

