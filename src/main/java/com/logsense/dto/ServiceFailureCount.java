package com.logsense.dto;

public record ServiceFailureCount(String service, long rootCauseCount, long affectedCount) {
}
