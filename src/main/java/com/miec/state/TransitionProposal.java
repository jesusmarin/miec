
package com.miec.state;

import java.time.Instant;
import java.util.Objects;

public record TransitionProposal(
        CycleState sourceState,
        CycleState proposedState,
        String reason,
        Instant proposedAt
) {

    public TransitionProposal {
        Objects.requireNonNull(sourceState,
                "Source state is required");

        Objects.requireNonNull(proposedState,
                "Proposed state is required");

        if (sourceState == proposedState) {
            throw new IllegalArgumentException(
                    "Source and proposed states must be different");
        }

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Transition reason is required");
        }

        reason = reason.trim();

        Objects.requireNonNull(proposedAt,
                "Proposal timestamp is required");
    }
}
