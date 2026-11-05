package com.logsense.domain.model;

/**
 * Failure categories with a "root-cause weight": infrastructure-level failures (database, retries exhausted
 * against a dependency) are more likely to be the origin of an incident than generic symptoms such as timeouts.
 */
public enum FailureCategory {
    DATABASE(5, "Database failure"),
    RETRY_EXHAUSTION(4, "Retry exhaustion"),
    COMMUNICATION(3, "Communication failure"),
    TIMEOUT(2, "Timeout"),
    MISSING_RESPONSE(1, "Missing response"),
    EXCEPTION(1, "Unhandled exception");

    private final int rootCauseWeight;
    private final String displayName;

    FailureCategory(int rootCauseWeight, String displayName) {
        this.rootCauseWeight = rootCauseWeight;
        this.displayName = displayName;
    }

    public int rootCauseWeight() {
        return rootCauseWeight;
    }

    public String displayName() {
        return displayName;
    }

    public static int maxWeight() {
        return 5;
    }
}
