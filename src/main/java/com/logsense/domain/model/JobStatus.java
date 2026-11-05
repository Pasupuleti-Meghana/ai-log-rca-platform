package com.logsense.domain.model;

public enum JobStatus {
    QUEUED, PROCESSING, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
