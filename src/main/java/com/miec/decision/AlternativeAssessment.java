
package com.miec.decision;

import java.util.List;
import java.util.Objects;

public record AlternativeAssessment(
        DecisionAction action,
        boolean admissible,
        String reason,
        String expectedBenefit,
        List<String> identifiedRisks,
        String estimatedCost,
        boolean reversible
) {

    public AlternativeAssessment {
        Objects.requireNonNull(action, "Decision action is required");

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Assessment reason is required");
        }

        reason = reason.trim();

        if (admissible
                && (expectedBenefit == null || expectedBenefit.isBlank())) {
            throw new IllegalArgumentException(
                    "Expected benefit is required for admissible alternatives");
        }

        if (expectedBenefit != null) {
            expectedBenefit = expectedBenefit.trim();
        }

        Objects.requireNonNull(
                identifiedRisks,
                "Identified risks are required");

        if (identifiedRisks.stream().anyMatch(
                risk -> risk == null || risk.isBlank())) {
            throw new IllegalArgumentException(
                    "Identified risks cannot contain null or blank elements");
        }

        identifiedRisks = identifiedRisks.stream()
                .map(String::trim)
                .toList();

        if (estimatedCost != null) {
            estimatedCost = estimatedCost.trim();

            if (estimatedCost.isBlank()) {
                throw new IllegalArgumentException(
                        "Estimated cost cannot be blank");
            }
        }
    }
}

