package com.logsense.processing.parser;

import com.logsense.domain.model.ParsedLogEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LogParserFactoryTest {

    private final LogParserFactory factory = LogParserFactory.withDefaultParsers();

    @Test
    void parsersAreOrderedByPriorityWithGenericLast() {
        assertThat(factory.parsers()).extracting(LogParser::format)
                .containsExactly(LogFormat.JSON, LogFormat.SPRING_BOOT, LogFormat.LOG4J, LogFormat.ACCESS_LOG,
                        LogFormat.GENERIC);
    }

    @Test
    void autoDetectsFormatPerLine() {
        assertThat(factory.parserFor("{\"message\":\"hi\",\"level\":\"INFO\"}")).map(LogParser::format)
                .contains(LogFormat.JSON);
        assertThat(factory.parserFor("2024-10-15T10:00:00.000Z  INFO 1 --- [main] c.a.App : started"))
                .map(LogParser::format).contains(LogFormat.SPRING_BOOT);
        assertThat(factory.parserFor("2024-10-15 10:00:00,000 [main] INFO com.a.App - started"))
                .map(LogParser::format).contains(LogFormat.LOG4J);
        assertThat(factory.parserFor("1.2.3.4 - - [15/Oct/2024:13:00:00 +0000] \"GET / HTTP/1.1\" 200 5"))
                .map(LogParser::format).contains(LogFormat.ACCESS_LOG);
        assertThat(factory.parserFor("2024-10-15 10:00:00 something happened")).map(LogParser::format)
                .contains(LogFormat.GENERIC);
        assertThat(factory.parserFor("\tat com.acme.Foo.bar(Foo.java:1)")).isEmpty();
    }

    @Test
    void detectsDominantFormatOfSampleByMajorityVote() {
        List<String> sample = List.of(
                "2024-10-15 10:00:00,000 [main] INFO com.a.App - one",
                "2024-10-15 10:00:01,000 [main] WARN com.a.App - two",
                "{\"message\":\"three\"}",
                "2024-10-15 10:00:02,000 [main] ERROR com.a.App - four");
        assertThat(factory.detectFormat(sample)).isEqualTo(LogFormat.LOG4J);
    }

    @Test
    void preferredParserIsTriedFirstAndFallsBackWhenItDoesNotMatch() {
        LogParser log4j = factory.parsers().stream().filter(p -> p.format() == LogFormat.LOG4J).findFirst()
                .orElseThrow();
        Optional<LogParserFactory.ParsedEventWithParser> json =
                factory.parse("{\"@timestamp\":\"2024-10-15T10:00:00Z\",\"message\":\"x\"}", 1, ParseContext.defaults(), log4j);
        assertThat(json).isPresent();
        assertThat(json.get().parser().format()).isEqualTo(LogFormat.JSON);
    }

    @Test
    void mixedFileIsReportedAsMixed() {
        LogEventAssembler assembler = new LogEventAssembler(factory, ParseContext.defaults());
        List<ParsedLogEvent> events = new ArrayList<>();
        String[] lines = {
                "{\"@timestamp\":\"2024-10-15T13:00:00Z\",\"message\":\"a\"}",
                "2024-10-15 13:00:01,000 [main] INFO com.a.App - b",
                "1.2.3.4 - - [15/Oct/2024:13:00:02 +0000] \"GET / HTTP/1.1\" 200 5",
                "2024-10-15T13:00:03.000Z  INFO 1 --- [x-service] [main] c.a.App : d"};
        for (int i = 0; i < lines.length; i++) {
            assembler.accept(lines[i], i + 1).ifPresent(events::add);
        }
        assembler.flush().ifPresent(events::add);
        assertThat(events).hasSize(4);
        assertThat(assembler.dominantFormat()).isEqualTo("MIXED");
    }
}