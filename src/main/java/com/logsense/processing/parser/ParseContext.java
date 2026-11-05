package com.logsense.processing.parser;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Per-file parsing context.
 *
 * @param defaultService service name used when a line does not carry one
 * @param defaultZone    zone applied to timestamps without offset information
 */
public record ParseContext(String defaultService, ZoneId defaultZone) {

    public static final String UNKNOWN_SERVICE = "unknown";

    public static ParseContext defaults() {
        return new ParseContext(UNKNOWN_SERVICE, ZoneOffset.UTC);
    }

    /** Derives a default service from file names such as {@code order-service.log} or {@code api-gateway-2024.log}. */
    public static ParseContext forFile(String fileName) {
        return new ParseContext(serviceFromFileName(fileName), ZoneOffset.UTC);
    }

    static String serviceFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return UNKNOWN_SERVICE;
        }
        String base = fileName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = base.indexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[-_]?\\d{4}[-_]?\\d{2}[-_]?\\d{2}.*$", "");
        return base.matches("[a-z][a-z0-9-]*(service|gateway|svc|api)") ? base : UNKNOWN_SERVICE;
    }
}
