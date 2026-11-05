package com.logsense.processing.masking;

import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Payment for john.doe@example.com failed | Payment for [EMAIL] failed",
            "card=4111 1111 1111 1111 amount=10 | card=[CARD] amount=10",
            "card 4111-1111-1111-1111 declined | card [CARD] declined",
            "Authorization: Bearer abcdefgh12345678 | Authorization: Bearer [TOKEN]",
            "password=hunter2 user=bob | password=[REDACTED] user=bob",
            "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln ok | jwt [TOKEN] ok"})
    void masksSensitiveData(String input, String expected) {
        assertThat(masker.mask(input)).isEqualTo(expected);
    }

    @Test
    void doesNotMaskNonLuhnNumbersOrTraceIds() {
        String text = "order 1234567890123 trace=7c2a8e4f1d9b3c50 took 5000ms";
        assertThat(masker.mask(text)).isEqualTo(text);
        assertThat(PiiMasker.luhnValid("4111111111111111")).isTrue();
        assertThat(PiiMasker.luhnValid("4111111111111112")).isFalse();
    }

    @Test
    void masksMessageAndStackTraceOfEvents() {
        ParsedLogEvent event = ParsedLogEvent.builder().level(LogLevel.ERROR)
                .message("Refund for jane@corp.io").stackTrace("java.lang.IllegalStateException: card 5555555555554444")
                .build();
        ParsedLogEvent masked = masker.mask(event);
        assertThat(masked.message()).isEqualTo("Refund for [EMAIL]");
        assertThat(masked.stackTrace()).contains("[CARD]").doesNotContain("5555");
    }

    @Test
    void disabledMaskerIsNoOp() {
        assertThat(new PiiMasker(false).mask("john@example.com")).isEqualTo("john@example.com");
        assertThat(masker.mask((String) null)).isNull();
    }
}