package com.logsense.processing.parser;

import com.logsense.domain.model.ParsedLogEvent;

import java.util.Optional;

/**
 * Strategy for parsing one log format. Implementations must be stateless and thread-safe.
 */
public interface LogParser {

    LogFormat format();

    /** Lower value = tried earlier. Generic fallbacks should use a high value. */
    int priority();

    /** Cheap pre-check (no full parse) used for format auto-detection. */
    boolean supports(String line);

    Optional<ParsedLogEvent> parse(String line, long lineNumber, ParseContext context);
}
