package com.logsense.processing.detection;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds references to other services (e.g. "timed out calling inventory-service") in free text. */
public final class ServiceReferenceExtractor {

    public static final Pattern SERVICE_NAME = Pattern.compile(
            "\\b([a-z][a-z0-9]*(?:-[a-z0-9]+)*-(?:service|gateway|svc|api))\\b");

    private ServiceReferenceExtractor() {
    }

    public static Optional<String> firstOtherService(String text, String ownService) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = SERVICE_NAME.matcher(text);
        while (m.find()) {
            String candidate = m.group(1);
            if (!candidate.equalsIgnoreCase(ownService)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
