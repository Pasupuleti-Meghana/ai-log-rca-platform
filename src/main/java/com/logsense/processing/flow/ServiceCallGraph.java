package com.logsense.processing.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directed graph of service calls (caller -> callee) with aggregated statistics. Thread-safe for concurrent
 * {@link #addCall} invocations.
 */
public class ServiceCallGraph {

    private final Map<String, Map<String, Stats>> adjacency = new ConcurrentHashMap<>();

    public void addCall(String caller, String callee, Long latencyMs, boolean error) {
        if (caller == null || callee == null || caller.equals(callee)) {
            return;
        }
        adjacency.computeIfAbsent(caller, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(callee, k -> new Stats())
                .record(latencyMs, error);
    }

    public void addEdge(CallEdge edge) {
        if (edge.caller() == null || edge.callee() == null || edge.caller().equals(edge.callee())) {
            return;
        }
        adjacency.computeIfAbsent(edge.caller(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(edge.callee(), k -> new Stats())
                .merge(edge);
    }

    public void merge(ServiceCallGraph other) {
        other.edges().forEach(this::addEdge);
    }

    public boolean isEmpty() {
        return adjacency.isEmpty();
    }

    public boolean hasEdge(String caller, String callee) {
        return adjacency.getOrDefault(caller, Map.of()).containsKey(callee);
    }

    public Set<String> callees(String service) {
        return Set.copyOf(adjacency.getOrDefault(service, Map.of()).keySet());
    }

    public Set<String> callers(String service) {
        Set<String> result = new LinkedHashSet<>();
        adjacency.forEach((caller, callees) -> {
            if (callees.containsKey(service)) {
                result.add(caller);
            }
        });
        return result;
    }

    /** All services transitively reachable downstream of {@code service} (BFS, cycle safe). */
    public Set<String> downstreamOf(String service) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(callees(service));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!next.equals(service) && visited.add(next)) {
                queue.addAll(callees(next));
            }
        }
        return visited;
    }

    public Set<String> services() {
        Set<String> all = new LinkedHashSet<>(adjacency.keySet());
        adjacency.values().forEach(m -> all.addAll(m.keySet()));
        return all;
    }

    public List<CallEdge> edges() {
        List<CallEdge> edges = new ArrayList<>();
        new TreeMap<>(adjacency).forEach((caller, callees) -> new TreeMap<>(callees)
                .forEach((callee, stats) -> edges.add(stats.toEdge(caller, callee))));
        edges.sort(Comparator.comparing(CallEdge::caller).thenComparing(CallEdge::callee));
        return edges;
    }

    private static final class Stats {
        private long calls;
        private long errors;
        private long latencySum;
        private long latencySamples;
        private Long maxLatency;

        synchronized void record(Long latencyMs, boolean error) {
            calls++;
            if (error) {
                errors++;
            }
            if (latencyMs != null && latencyMs >= 0) {
                latencySum += latencyMs;
                latencySamples++;
                maxLatency = maxLatency == null ? latencyMs : Math.max(maxLatency, latencyMs);
            }
        }

        synchronized void merge(CallEdge edge) {
            calls += edge.callCount();
            errors += edge.errorCount();
            if (edge.avgLatencyMs() != null) {
                long samples = Math.max(1, edge.callCount());
                latencySum += Math.round(edge.avgLatencyMs() * samples);
                latencySamples += samples;
            }
            if (edge.maxLatencyMs() != null) {
                maxLatency = maxLatency == null ? edge.maxLatencyMs() : Math.max(maxLatency, edge.maxLatencyMs());
            }
        }

        synchronized CallEdge toEdge(String caller, String callee) {
            Double avg = latencySamples == 0 ? null : (double) latencySum / latencySamples;
            return new CallEdge(caller, callee, calls, errors, avg, maxLatency);
        }
    }
}
