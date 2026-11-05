package com.logsense.processing.parser;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TimestampParserTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "2024-10-15T10:00:00.123Z        | 2024-10-15T10:00:00.123Z",
            "2024-10-15T12:00:00.123+02:00   | 2024-10-15T10:00:00.123Z",
            "2024-10-15 10:00:00,123         | 2024-10-15T10:00:00.123Z",
            "2024-10-15 10:00:00.123456      | 2024-10-15T10:00:00.123456Z",
            "2024-10-15T10:00:00             | 2024-10-15T10:00:00Z",
            "15/Oct/2024:12:00:00 +0200      | 2024-10-15T10:00:00Z",
            "1728986400123                   | 2024-10-15T10:00:00.123Z",
            "1728986400                      | 2024-10-15T10:00:00Z"
    })
    void normalisesSupportedFormatsToUtcInstant(String raw, String expected) {
        assertThat(TimestampParser.parse(raw.trim(), ZoneOffset.UTC)).contains(Instant.parse(expected.trim()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a date", "2024-13-45T99:00:00"})
    void returnsEmptyForGarbage(String raw) {
        assertThat(TimestampParser.parse(raw, ZoneOffset.UTC)).isEmpty();
    }
}