package com.logsense.processing.detection;

import com.logsense.domain.model.Finding;
import com.logsense.domain.model.ParsedLogEvent;

import java.util.Optional;

/**
 * One link of the detection chain of responsibility. Detectors are ordered from most specific to most generic;
 * the first detector that recognises an event "handles" it.
 */
public interface FailureDetector {

    /** Lower value = earlier in the chain. */
    int order();

    String name();

    Optional<Finding> detect(ParsedLogEvent event);
}
