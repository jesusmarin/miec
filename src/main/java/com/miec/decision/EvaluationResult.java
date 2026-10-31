package com.miec.decision;
import com.miec.evidence.EvidenceAssessment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EvaluationResult(
        UUID id,
        UUID cycleId,
        UUID objectiveId,
        ResultClassification classification,
        EvidenceAssessment evidenceAssessment,
        ConfidenceLevel confidenceLevel,
        String rationale,
        Instant evaluatedAt
) {

    public EvaluationResult {
        Objects.requireNonNull(id, "Evaluation ID is required");
        Objects.requireNonNull(cycleId, "Cycle ID is required");
        Objects.requireNonNull(objectiveId, "Objective ID is required");
        Objects.requireNonNull(classification,
                "Result classification is required");
        Objects.requireNonNull(evidenceAssessment,
                "Evidence assessment is required");
        Objects.requireNonNull(confidenceLevel,
                "Confidence level is required");
        Objects.requireNonNull(evaluatedAt,
                "Evaluation timestamp is required");

        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException(
                    "Evaluation rationale is required");
        }

        if (!evidenceAssessment.allowsEvaluation()
                && classification != ResultClassification.INCONCLUSIVE) {
            throw new IllegalArgumentException(
                    "Insufficient evidence cannot support a conclusive result");
        }
    }

}
