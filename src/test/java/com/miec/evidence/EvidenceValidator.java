package com.miec.evidence;

public interface EvidenceValidator {
    EvidenceAssessment validate(EvidenceValidationRequest request);
}
