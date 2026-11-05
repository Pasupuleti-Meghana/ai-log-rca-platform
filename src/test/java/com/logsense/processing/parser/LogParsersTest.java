package com.logsense.processing.parser;

import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LogParsersTest {

    private static final ParseContext CTX = ParseContext.defaults();

    @Nested
    class SpringBoot {
        private final SpringBootLogParser parser = new SpringBootLogParser();

        @Test
        void parsesSpringBoot3LineWithServiceThreadAndTraceSpan() {
            String line = "2024-10-15T10:00:05.200Z ERROR 1 --- [order-service] [nio-8081-exec-7] "
                    + "[7c2a8e4f1d9b3c50,e5f60718293a4b5c] c.l.order.InventoryClient : Read timed out calling inventory-service";
            ParsedLogEvent e = parser.parse(line, 7, CTX).orElseThrow();
            assertThat(e.timestamp()).isEqualTo(Instant.parse("2024-10-15T10:00:05.200Z"));
            assertThat(e.level()).isEqualTo(LogLevel.ERROR);
            assertThat(e.service()).isEqualTo("order-service");
            assertThat(e.thread()).isEqualTo("nio-8081-exec-7");
            assertThat(e.traceId()).isEqualTo("7c2a8e4f1d9b3c50");
            assertThat(e.spanId()).isEqualTo("e5f60718293a4b5c");
            assertThat(e.logger()).isEqualTo("c.l.order.InventoryClient");
            assertThat(e.message()).isEqualTo("Read timed out calling inventory-service");
            assertThat(e.lineNumber()).isEqualTo(7);
        }

        @Test
        void classicPatternWithoutServiceUsesContextDefault() {
            String line = "2024-10-15 10:00:00.100  INFO 4242 --- [           main] o.s.b.w.e.tomcat.TomcatWebServer : Tomcat started on port 8080";
            ParsedLogEvent e = parser.parse(line, 1, new ParseContext("billing-service", java.time.ZoneOffset.UTC))
                    .orElseThrow();
            assertThat(e.thread()).isEqualTo("main");
            assertThat(e.service()).isEqualTo("billing-service");
            assertThat(e.level()).isEqualTo(LogLevel.INFO);
        }

        @Test
        void rejectsOtherFormats() {
            assertThat(parser.supports("{\"message\":\"x\"}")).isFalse();
            assertThat(parser.parse("random text", 1, CTX)).isEmpty();
        }
    }

    @Nested
    class Log4j {
        private final Log4jLogParser parser = new Log4jLogParser();

        @Test
        void parsesLog4jLineAndExtractsKeyValueContext() {
            String line = "2024-10-15 11:20:03,150 [http-nio-8082-exec-3] ERROR io.github.resilience4j.retry.Retry - "
                    + "service=payment-service traceId=ab12cd34ef56ab78 spanId=1b2c3d4e5f6a7b8c parentSpanId=0a1b2c3d4e5f6a7b "
                    + "Retry attempt 3/3 failed";
            ParsedLogEvent e = parser.parse(line, 3, CTX).orElseThrow();
            assertThat(e.timestamp()).isEqualTo(Instant.parse("2024-10-15T11:20:03.150Z"));
            assertThat(e.thread()).isEqualTo("http-nio-8082-exec-3");
            assertThat(e.level()).isEqualTo(LogLevel.ERROR);
            assertThat(e.service()).isEqualTo("payment-service");
            assertThat(e.traceId()).isEqualTo("ab12cd34ef56ab78");
            assertThat(e.spanId()).isEqualTo("1b2c3d4e5f6a7b8c");
            assertThat(e.parentSpanId()).isEqualTo("0a1b2c3d4e5f6a7b");
            assertThat(e.message()).contains("Retry attempt 3/3 failed");
        }

        @Test
        void supportsLevelBeforeThreadVariant() {
            String line = "2024-10-15 11:20:00,050 WARN [worker-1] com.acme.Job - correlationId=REQ-99 slow job";
            ParsedLogEvent e = parser.parse(line, 1, CTX).orElseThrow();
            assertThat(e.level()).isEqualTo(LogLevel.WARN);
            assertThat(e.thread()).isEqualTo("worker-1");
            assertThat(e.correlationId()).isEqualTo("REQ-99");
        }
    }

    @Nested
    class Json {
        private final JsonLogParser parser = new JsonLogParser();

        @Test
        void parsesFlatEcsKeysIncludingStackTrace() {
            String line = "{\"@timestamp\":\"2024-10-15T12:00:02.150Z\",\"log.level\":\"ERROR\",\"service.name\":\"order-service\","
                    + "\"process.thread.name\":\"exec-1\",\"trace.id\":\"c0ffee11d00d4a5b\",\"span.id\":\"bb11cc22dd33ee44\","
                    + "\"parent.id\":\"aa00bb11cc22dd33\",\"message\":\"deadlock detected\",\"error.type\":\"org.postgresql.util.PSQLException\","
                    + "\"error.stack_trace\":\"org.postgresql.util.PSQLException: deadlock\\n\\tat a.b.C.d(C.java:1)\"}";
            ParsedLogEvent e = parser.parse(line, 5, CTX).orElseThrow();
            assertThat(e.service()).isEqualTo("order-service");
            assertThat(e.level()).isEqualTo(LogLevel.ERROR);
            assertThat(e.traceId()).isEqualTo("c0ffee11d00d4a5b");
            assertThat(e.parentSpanId()).isEqualTo("aa00bb11cc22dd33");
            assertThat(e.exceptionClass()).isEqualTo("org.postgresql.util.PSQLException");
            assertThat(e.stackTrace()).contains("C.java:1");
            assertThat(e.format()).isEqualTo("JSON");
        }

        @Test
        void parsesNestedLogbackStyleKeysAndHttpStatus() {
            String line = "{\"timestamp\":\"2024-10-15T12:00:02.190Z\",\"level\":\"WARN\",\"service\":{\"name\":\"api-gateway\"},"
                    + "\"traceId\":\"c0ffee11d00d4a5b\",\"msg\":\"upstream failed\",\"http\":{\"response\":{\"status_code\":502}},\"duration_ms\":120}";
            ParsedLogEvent e = parser.parse(line, 1, CTX).orElseThrow();
            assertThat(e.service()).isEqualTo("api-gateway");
            assertThat(e.level()).isEqualTo(LogLevel.WARN);
            assertThat(e.httpStatus()).isEqualTo(502);
            assertThat(e.durationMs()).isEqualTo(120L);
            assertThat(e.message()).isEqualTo("upstream failed");
        }

        @Test
        void invalidJsonIsNotParsed() {
            assertThat(parser.parse("{not json}", 1, CTX)).isEmpty();
        }
    }

    @Nested
    class AccessLog {
        private final AccessLogParser parser = new AccessLogParser();

        @Test
        void parsesNginxCombinedLogWithStatusDerivedLevel() {
            String line = "10.0.0.15 - - [15/Oct/2024:13:00:01 +0000] \"POST /api/orders/1/pay HTTP/1.1\" 502 157 \"-\" "
                    + "\"curl/8.4.0\" service=api-gateway upstream=order-service traceId=d1e2f3a4b5c6d7e8 request_time=0.061";
            ParsedLogEvent e = parser.parse(line, 2, CTX).orElseThrow();
            assertThat(e.timestamp()).isEqualTo(Instant.parse("2024-10-15T13:00:01Z"));
            assertThat(e.httpStatus()).isEqualTo(502);
            assertThat(e.level()).isEqualTo(LogLevel.ERROR);
            assertThat(e.service()).isEqualTo("api-gateway");
            assertThat(e.traceId()).isEqualTo("d1e2f3a4b5c6d7e8");
            assertThat(e.message()).startsWith("POST /api/orders/1/pay -> 502");
        }

        @Test
        void clientErrorsAreWarnAndSuccessIsInfo() {
            assertThat(AccessLogParser.levelForStatus(404)).isEqualTo(LogLevel.WARN);
            assertThat(AccessLogParser.levelForStatus(200)).isEqualTo(LogLevel.INFO);
            assertThat(AccessLogParser.levelForStatus(504)).isEqualTo(LogLevel.ERROR);
        }
    }

    @Nested
    class Generic {
        private final GenericLogParser parser = new GenericLogParser();

        @Test
        void fallbackExtractsTimestampLevelAndBracketedService() {
            ParsedLogEvent e = parser.parse("2024-10-15 10:00:00 | ERROR | [shipping-service] carrier API down", 9, CTX)
                    .orElseThrow();
            assertThat(e.timestamp()).isEqualTo(Instant.parse("2024-10-15T10:00:00Z"));
            assertThat(e.level()).isEqualTo(LogLevel.ERROR);
            assertThat(e.service()).isEqualTo("shipping-service");
            assertThat(e.message()).contains("carrier API down");
        }
    }
}