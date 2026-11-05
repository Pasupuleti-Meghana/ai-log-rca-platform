package com.logsense.processing.parser;

import com.logsense.domain.model.ParsedLogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogEventAssemblerTest {

    private static List<ParsedLogEvent> assemble(LogEventAssembler assembler, String text) {
        List<ParsedLogEvent> out = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            assembler.accept(lines[i], i + 1).ifPresent(out::add);
        }
        assembler.flush().ifPresent(out::add);
        return out;
    }

    @Test
    void appendsMultiLineStackTraceToPreviousEventAndExtractsRootException() {
        String text = """
                2024-10-15T10:00:30.210Z ERROR 1 --- [inventory-service] [exec-5] [7c2a8e4f1d9b3c50,f60718293a4b5c6d] c.l.StockService : Failed to reserve stock
                org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
                \tat org.springframework.jdbc.datasource.DataSourceUtils.getConnection(DataSourceUtils.java:84)
                Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available
                \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)
                \t... 12 more
                2024-10-15T10:00:31.000Z  INFO 1 --- [inventory-service] [exec-5] [] c.l.StockService : next event""";
        LogEventAssembler assembler = new LogEventAssembler(LogParserFactory.withDefaultParsers(),
                ParseContext.defaults());
        List<ParsedLogEvent> events = assemble(assembler, text);

        assertThat(events).hasSize(2);
        ParsedLogEvent error = events.getFirst();
        assertThat(error.exceptionClass()).isEqualTo("org.springframework.jdbc.CannotGetJdbcConnectionException");
        assertThat(error.stackTrace())
                .contains("Caused by: java.sql.SQLTransientConnectionException")
                .contains("... 12 more");
        assertThat(error.searchableText()).contains("HikariPool-1 - Connection is not available");
        assertThat(events.get(1).message()).isEqualTo("next event");
        assertThat(assembler.unparsedLines()).isZero();
    }

    @Test
    void orphanContinuationLinesAtStartOfFileAreCountedAsUnparsed() {
        LogEventAssembler assembler = new LogEventAssembler(LogParserFactory.withDefaultParsers(),
                ParseContext.defaults());
        List<ParsedLogEvent> events = assemble(assembler, "\tat a.b.C.d(C.java:1)\n\tat a.b.C.e(C.java:2)");
        assertThat(events).isEmpty();
        assertThat(assembler.unparsedLines()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\tat com.acme.Foo.bar(Foo.java:10)", "    at com.acme.Foo.bar(Foo.java:10)",
            "Caused by: java.io.IOException: boom", "\t... 42 more", "java.lang.IllegalStateException: bad state",
            "Suppressed: java.lang.RuntimeException"})
    void recognisesContinuationLines(String line) {
        assertThat(LogEventAssembler.isContinuation(line)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-10-15 10:00:00,000 [main] INFO com.a.App - started", "{\"message\":\"x\"}"})
    void newEventsAreNotContinuations(String line) {
        assertThat(LogEventAssembler.isContinuation(line)).isFalse();
    }
}