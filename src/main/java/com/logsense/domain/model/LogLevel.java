package com.logsense.domain.model;

import java.util.Locale;

public enum LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN;

    public static LogLevel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE", "FINEST", "FINER" -> TRACE;
            case "DEBUG", "FINE" -> DEBUG;
            case "INFO", "INFORMATION", "NOTICE" -> INFO;
            case "WARN", "WARNING" -> WARN;
            case "ERROR", "ERR", "SEVERE" -> ERROR;
            case "FATAL", "CRITICAL", "CRIT", "EMERG", "ALERT" -> FATAL;
            default -> UNKNOWN;
        };
    }

    public boolean isProblem() {
        return this == WARN || this == ERROR || this == FATAL;
    }

    public boolean isError() {
        return this == ERROR || this == FATAL;
    }
}
