package com.miec.decision;

public enum ResultClassification {
    FAVORABLE("RS-01"),
    PARTIAL("RS-02"),
    NEUTRAL("RS-03"),
    UNFAVORABLE("RS-04"),
    INCONCLUSIVE("RS-05"),
    RISK_INADMISSIBLE("RS-06");

    private final String code;

    ResultClassification(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean isFavorable() {
        return this == FAVORABLE;
    }

    public boolean isConclusive() {
        return this != INCONCLUSIVE;
    }

    public boolean blocksContinuation() {
        return this == RISK_INADMISSIBLE;
    }

}
