package com.miec.policy;

public enum AutonomyLevel {
    OBSERVATION("A0"),
    ASSISTANCE("A1"),
    SUPERVISED_EXECUTION("A2"),
    BOUNDED_AUTONOMY("A3"),
    EXTENDED_AUTONOMY("A4");

    private final String code;

    AutonomyLevel(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean allowsAutonomousExecution() {
        return this == BOUNDED_AUTONOMY
                || this == EXTENDED_AUTONOMY;
    }

}
