package com.logsense.domain.model;

public enum Severity {
    LOW, MEDIUM, HIGH, CRITICAL;

    public Severity max(Severity other) {
        return other != null && other.ordinal() > ordinal() ? other : this;
    }
}
