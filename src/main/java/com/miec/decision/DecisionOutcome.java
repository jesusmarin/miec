
package com.miec.decision;

import com.miec.state.TransitionProposal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record DecisionOutcome(
        EvaluationResult evaluation,
        List<AlternativeAssessment> alternatives,
        DecisionRecommendation recommendation,
        TransitionProposal transitionProposal
) {

    public DecisionOutcome {
        Objects.requireNonNull(evaluation,
                "Evaluation result is required");

        Objects.requireNonNull(alternatives,
                "Alternatives list is required");

        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one alternative is required");
        }

        alternatives = List.copyOf(alternatives);

        Objects.requireNonNull(recommendation,
                "Decision recommendation is required");

        Objects.requireNonNull(transitionProposal,
                "Transition proposal is required");

        Set<DecisionAction> assessedActions = new HashSet<>();

        for (AlternativeAssessment alternative : alternatives) {
            if (!assessedActions.add(alternative.action())) {
                throw new IllegalArgumentException(
                        "Duplicate alternative action: "
                                + alternative.action());
            }
        }

        boolean recommendedActionIsAdmissible =
                alternatives.stream().anyMatch(
                        alternative ->
                                alternative.action() == recommendation.action()
                                        && alternative.admissible()
                );

        if (!recommendedActionIsAdmissible) {
            throw new IllegalArgumentException(
                    "Recommended action must be admissible");
        }
    }
}

