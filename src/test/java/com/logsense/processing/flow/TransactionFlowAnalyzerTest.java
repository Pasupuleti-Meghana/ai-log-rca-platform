package com.logsense.processing.flow;

import com.logsense.domain.model.FlowEvent;
import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.logsense.support.TestEvents.T0;
import static com.logsense.support.TestEvents.flow;
import static com.logsense.support.TestEvents.span;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionFlowAnalyzerTest {

    private final TransactionFlowAnalyzer analyzer = new TransactionFlowAnalyzer(Duration.ofSeconds(2));

    @Test
    void buildsOrderedTimelineAndSpanBasedCallGraph() {
        List<FlowEvent> events = List.of(
                span(30, "inventory-service", "s3", "s2", LogLevel.INFO),
                span(0, "api-gateway", "s1", null, LogLevel.INFO),
                span(10, "order-service", "s2", "s1", LogLevel.INFO),
                span(50, "inventory-service", "s3", "s2", LogLevel.ERROR));

        TransactionFlow flow = analyzer.analyze("t1", events);

        assertThat(flow.timeline()).extracting(FlowStep::offsetMs).containsExactly(0L, 10L, 30L, 50L);
        assertThat(flow.services()).containsExactly("api-gateway", "order-service", "inventory-service");
        assertThat(flow.durationMs()).isEqualTo(50);
        assertThat(flow.edges()).extracting(e -> e.caller() + "->" + e.callee())
                .containsExactlyInAnyOrder("api-gateway->order-service", "order-service->inventory-service");
        CallEdge inv = flow.edges().stream().filter(e -> e.callee().equals("inventory-service")).findFirst()
                .orElseThrow();
        assertThat(inv.errorCount()).isEqualTo(1);
        assertThat(flow.failed()).isTrue();
    }

    @Test
    void buildsEdgesFromMessageHeuristicsWithLatency() {
        TransactionFlow flow = analyzer.analyze("t2", List.of(
                flow(0, "order-service", LogLevel.INFO, "Calling inventory-service to reserve items"),
                flow(20, "inventory-service", LogLevel.INFO, "Reserved"),
                flow(45, "order-service", LogLevel.INFO, "Received response from inventory-service status=200 in 45ms")));

        assertThat(flow.edges()).hasSize(1);
        CallEdge edge = flow.edges().getFirst();
        assertThat(edge.caller()).isEqualTo("order-service");
        assertThat(edge.callee()).isEqualTo("inventory-service");
        assertThat(edge.callCount()).isEqualTo(1);
        assertThat(edge.errorCount()).isZero();
        assertThat(edge.avgLatencyMs()).isEqualTo(45.0);
        assertThat(flow.missingResponses()).isEmpty();
        assertThat(flow.failed()).isFalse();
    }

    @Test
    void detectsMissingResponseWhenRequestIsNeverAnswered() {
        TransactionFlow flow = analyzer.analyze("t3", List.of(
                flow(0, "api-gateway", LogLevel.INFO, "Routing request to order-service"),
                flow(10, "order-service", LogLevel.INFO, "Calling payment-service"),
                flow(5000, "api-gateway", LogLevel.INFO, "Received response from order-service status=200 in 5000ms")));

        assertThat(flow.missingResponses()).anySatisfy(m -> {
            assertThat(m.caller()).isEqualTo("order-service");
            assertThat(m.callee()).isEqualTo("payment-service");
            assertThat(m.respondedAt()).isNull();
            assertThat(m.reason()).contains("No response");
        });
        assertThat(flow.failed()).isTrue();
    }

    @Test
    void lateResponseBeyondTimeoutIsReportedAsMissing() {
        TransactionFlow flow = analyzer.analyze("t4", List.of(
                flow(0, "api-gateway", LogLevel.INFO, "Routing request to order-service"),
                flow(5000, "api-gateway", LogLevel.INFO, "Received response from order-service status=200 in 5000ms")));

        assertThat(flow.missingResponses()).singleElement().satisfies(m -> {
            assertThat(m.callee()).isEqualTo("order-service");
            assertThat(m.respondedAt()).isEqualTo(T0.plusMillis(5000));
            assertThat(m.waitedMs()).isEqualTo(5000L);
        });
    }

    @Test
    void errorInCallerMentioningPendingCalleeClosesRequestAsFailedCall() {
        TransactionFlow flow = analyzer.analyze("t5", List.of(
                flow(0, "order-service", LogLevel.INFO, "Calling inventory-service"),
                flow(5000, "order-service", LogLevel.ERROR, "Read timed out calling inventory-service after 5000ms")));

        assertThat(flow.missingResponses()).isEmpty();
        assertThat(flow.edges()).singleElement().satisfies(e -> {
            assertThat(e.errorCount()).isEqualTo(1);
            assertThat(e.callee()).isEqualTo("inventory-service");
        });
    }

    @Test
    void traceAggregatorGroupsByTraceAndRespectsCaps() {
        TraceAggregator aggregator = new TraceAggregator(2, 2);
        for (int i = 0; i < 3; i++) {
            for (String trace : List.of("a", "b", "c")) {
                aggregator.accept(ParsedLogEvent.builder().lineNumber(i).timestamp(T0.plusMillis(i))
                        .service("x-service").level(LogLevel.INFO).traceId(trace).message("m").build());
            }
        }
        aggregator.accept(ParsedLogEvent.builder().lineNumber(99).timestamp(T0).service("x").message("no trace").build());

        assertThat(aggregator.traceCount()).isEqualTo(2);
        assertThat(aggregator.traceIds()).containsExactlyInAnyOrder("a", "b");
        assertThat(aggregator.events("a")).hasSize(2);
        assertThat(aggregator.droppedEvents()).isPositive();
    }

    @Test
    void serviceCallGraphComputesTransitiveDownstream() {
        ServiceCallGraph graph = new ServiceCallGraph();
        graph.addCall("api-gateway", "order-service", 10L, false);
        graph.addCall("order-service", "inventory-service", 30L, true);
        graph.addCall("order-service", "inventory-service", 50L, false);

        assertThat(graph.downstreamOf("api-gateway")).containsExactlyInAnyOrder("order-service", "inventory-service");
        assertThat(graph.callers("inventory-service")).containsExactly("order-service");
        CallEdge edge = graph.edges().stream().filter(e -> e.callee().equals("inventory-service")).findFirst()
                .orElseThrow();
        assertThat(edge.callCount()).isEqualTo(2);
        assertThat(edge.errorCount()).isEqualTo(1);
        assertThat(edge.avgLatencyMs()).isEqualTo(40.0);
    }
}