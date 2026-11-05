package com.logsense.processing.flow;

import com.logsense.config.LogSenseProperties;
import com.logsense.domain.model.FlowEvent;
import com.logsense.domain.model.LogLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconstructs a transaction from the events of one trace:
 * <ol>
 *   <li>ordered timeline with relative offsets,</li>
 *   <li>service call edges from span parent relationships ({@code parentSpanId -> spanId}),</li>
 *   <li>service call edges from message heuristics ("calling X" / "received response from X"),</li>
 *   <li>missing responses: requests without a matching response within the timeout window.</li>
 * </ol>
 */
@Component
public class TransactionFlowAnalyzer {

    private static final String SERVICE = "(?<svc>[a-z][a-z0-9]*(?:-[a-z0-9]+)+)";
    private static final Pattern REQUEST = Pattern.compile(
            "(?i)\\b(?:calling|invoking|forwarding (?:request )?to|sending request to|routing (?:request )?to)\\s+"
                    + SERVICE);
    private static final Pattern RESPONSE = Pattern.compile(
            "(?i)\\b(?:received response from|got response from|response from)\\s+" + SERVICE);
    private static final Pattern STATUS = Pattern.compile("(?i)status[=: ]\\s*(\\d{3})");
    private static final Pattern LATENCY = Pattern.compile("(?i)\\b(?:in|took|after)\\s+(\\d+)\\s*ms");

    private final Duration responseTimeout;

    @Autowired
    public TransactionFlowAnalyzer(LogSenseProperties properties) {
        this(properties.processing().missingResponseTimeout());
    }

    public TransactionFlowAnalyzer(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public TransactionFlow analyze(String traceId, Collection<FlowEvent> rawEvents) {
        List<FlowEvent> events = rawEvents.stream()
                .filter(e -> e.timestamp() != null)
                .sorted(Comparator.comparing(FlowEvent::timestamp).thenComparingLong(FlowEvent::lineNumber))
                .toList();
        if (events.isEmpty()) {
            return new TransactionFlow(traceId, null, null, 0, List.of(), List.of(), List.of(), List.of(), false);
        }
        Instant start = events.getFirst().timestamp();
        Instant end = events.getLast().timestamp();

        ServiceCallGraph graph = new ServiceCallGraph();
        Set<String> spanPairs = spanEdges(events, graph);
        List<MissingResponse> missing = heuristicEdges(traceId, events, graph, spanPairs);

        Set<String> services = new LinkedHashSet<>();
        List<FlowStep> timeline = new ArrayList<>(events.size());
        for (FlowEvent e : events) {
            services.add(e.service());
            timeline.add(new FlowStep(e.timestamp(), Duration.between(start, e.timestamp()).toMillis(), e.service(),
                    e.level(), e.spanId(), e.message()));
        }
        boolean failed = events.stream().anyMatch(FlowEvent::isError) || !missing.isEmpty();
        return new TransactionFlow(traceId, start, end, Duration.between(start, end).toMillis(),
                List.copyOf(services), timeline, graph.edges(), missing, failed);
    }

    private Set<String> spanEdges(List<FlowEvent> events, ServiceCallGraph graph) {
        Map<String, String> spanService = new HashMap<>();
        Map<String, Instant[]> spanWindow = new HashMap<>();
        Map<String, Boolean> spanError = new HashMap<>();
        Map<String, String> spanParent = new LinkedHashMap<>();
        for (FlowEvent e : events) {
            if (e.spanId() == null) {
                continue;
            }
            spanService.putIfAbsent(e.spanId(), e.service());
            spanWindow.merge(e.spanId(), new Instant[]{e.timestamp(), e.timestamp()},
                    (a, b) -> new Instant[]{a[0], b[1]});
            spanError.merge(e.spanId(), e.isError(), Boolean::logicalOr);
            if (e.parentSpanId() != null) {
                spanParent.putIfAbsent(e.spanId(), e.parentSpanId());
            }
        }
        Set<String> pairs = new HashSet<>();
        spanParent.forEach((span, parent) -> {
            String caller = spanService.get(parent);
            String callee = spanService.get(span);
            if (caller != null && callee != null && !caller.equals(callee)) {
                Instant[] w = spanWindow.get(span);
                graph.addCall(caller, callee, Duration.between(w[0], w[1]).toMillis(), spanError.get(span));
                pairs.add(caller + "->" + callee);
            }
        });
        return pairs;
    }

    private List<MissingResponse> heuristicEdges(String traceId, List<FlowEvent> events, ServiceCallGraph graph,
                                                 Set<String> spanPairs) {
        Map<String, Deque<FlowEvent>> pending = new LinkedHashMap<>();
        List<MissingResponse> missing = new ArrayList<>();
        for (FlowEvent e : events) {
            String msg = e.message() == null ? "" : e.message();
            if (e.isError() || e.level() == LogLevel.WARN) {
                Optional<String> failedCallee = pendingCalleeMentioned(pending, e.service(), msg);
                if (failedCallee.isPresent()) {
                    FlowEvent request = pending.get(key(e.service(), failedCallee.get())).poll();
                    record(graph, spanPairs, e.service(), failedCallee.get(), latency(request, e, msg), true);
                    continue;
                }
            }
            Matcher response = RESPONSE.matcher(msg);
            if (response.find()) {
                String callee = response.group("svc").toLowerCase(Locale.ROOT);
                Deque<FlowEvent> queue = pending.get(key(e.service(), callee));
                FlowEvent request = queue == null ? null : queue.poll();
                Long latency = latency(request, e, msg);
                Matcher st = STATUS.matcher(msg);
                boolean error = st.find() && Integer.parseInt(st.group(1)) >= 500;
                record(graph, spanPairs, e.service(), callee, latency, error);
                if (request != null && latency != null && latency > responseTimeout.toMillis()) {
                    missing.add(new MissingResponse(traceId, e.service(), callee, request.timestamp(), e.timestamp(),
                            latency, "Response arrived after timeout window of " + responseTimeout.toMillis() + " ms"));
                }
                continue;
            }
            Matcher request = REQUEST.matcher(msg);
            if (request.find()) {
                String callee = request.group("svc").toLowerCase(Locale.ROOT);
                if (!callee.equalsIgnoreCase(e.service())) {
                    pending.computeIfAbsent(key(e.service(), callee), k -> new ArrayDeque<>()).add(e);
                }
            }
        }
        Instant traceEnd = events.getLast().timestamp();
        pending.forEach((k, queue) -> queue.forEach(req -> {
            String[] parts = k.split("->", 2);
            record(graph, spanPairs, parts[0], parts[1], null, true);
            missing.add(new MissingResponse(traceId, parts[0], parts[1], req.timestamp(), null,
                    Duration.between(req.timestamp(), traceEnd).toMillis(), "No response observed"));
        }));
        return missing;
    }

    private static Optional<String> pendingCalleeMentioned(Map<String, Deque<FlowEvent>> pending, String caller,
                                                           String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        String prefix = caller + "->";
        return pending.entrySet().stream()
                .filter(en -> en.getKey().startsWith(prefix) && !en.getValue().isEmpty())
                .map(en -> en.getKey().substring(prefix.length()))
                .filter(lower::contains)
                .findFirst();
    }

    private static void record(ServiceCallGraph graph, Set<String> spanPairs, String caller, String callee,
                               Long latency, boolean error) {
        if (!spanPairs.contains(caller + "->" + callee)) {
            graph.addCall(caller, callee, latency, error);
        }
    }

    private static Long latency(FlowEvent request, FlowEvent response, String msg) {
        Matcher m = LATENCY.matcher(msg);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        if (response.durationMs() != null) {
            return response.durationMs();
        }
        return request == null ? null : Duration.between(request.timestamp(), response.timestamp()).toMillis();
    }

    private static String key(String caller, String callee) {
        return caller + "->" + callee;
    }
}
