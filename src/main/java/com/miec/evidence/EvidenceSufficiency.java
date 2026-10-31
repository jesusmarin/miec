package com.miec.evidence;

public enum EvidenceSufficiency {
    NOT_EVALUABLE("EV-0"),
    INSUFFICIENT("EV-1"),
    ACCEPTABLE_WITH_LIMITATIONS("EV-2"),
    SUFFICIENT("EV-3"),
    CORROBORATED("EV-4");

    private final String code;

    EvidenceSufficiency(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean allowsEvaluation() {
        return this == ACCEPTABLE_WITH_LIMITATIONS
                || this == SUFFICIENT
                || this == CORROBORATED;
    }

    public boolean hasLimitations() {
        return this == ACCEPTABLE_WITH_LIMITATIONS;
    }

}
