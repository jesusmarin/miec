package com.miec.state;

import java.time.Instant;
import java.util.List;

public record TransitionValidationResult (
        boolean allowed,
        CycleState currentState,
        CycleState proposedState,
        String reason,
        List<String> violatedInvariants,
        Instant validatedAt
) {
}
