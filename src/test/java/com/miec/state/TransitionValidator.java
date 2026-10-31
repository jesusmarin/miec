package com.miec.state;

public interface TransitionValidator {
    TransitionValidationResult validate(TransitionValidationRequest request);
}
