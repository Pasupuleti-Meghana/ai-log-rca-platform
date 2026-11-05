package com.logsense.processing.flow;

import com.logsense.domain.model.FlowEvent;
import com.logsense.domain.model.ParsedLogEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Lock-free per-trace aggregation used by parallel chunk workers. Memory is bounded by {@code maxTraces} and
 * {@code maxEventsPerTrace}; overflow is counted, not stored (graceful degradation for huge files).
 */
public class TraceAggregator {

    private final Map<String, TraceBuffer> traces = new ConcurrentHashMap<>();
    private final int maxTraces;
    private final int maxEventsPerTrace;
    private final AtomicLong droppedEvents = new AtomicLong();

    public TraceAggregator(int maxTraces, int maxEventsPerTrace) {
        this.maxTraces = maxTraces;
        this.maxEventsPerTrace = maxEventsPerTrace;
    }

    public void accept(ParsedLogEvent event) {
        String traceId = event.traceId();
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        TraceBuffer buffer = traces.get(traceId);
        if (buffer == null) {
            if (traces.size() >= maxTraces) {
                droppedEvents.incrementAndGet();
                return;
            }
            buffer = traces.computeIfAbsent(traceId, k -> new TraceBuffer());
        }
        if (buffer.size.incrementAndGet() > maxEventsPerTrace) {
            droppedEvents.incrementAndGet();
            return;
        }
        buffer.events.add(FlowEvent.from(event));
    }

    public int traceCount() {
        return traces.size();
    }

    public Set<String> traceIds() {
        return Set.copyOf(traces.keySet());
    }

    public long droppedEvents() {
        return droppedEvents.get();
    }

    public List<FlowEvent> events(String traceId) {
        TraceBuffer buffer = traces.get(traceId);
        if (buffer == null) {
            return List.of();
        }
        return buffer.events.stream()
                .sorted(Comparator.comparing(FlowEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(FlowEvent::lineNumber))
                .toList();
    }

    public void forEachTrace(BiConsumer<String, List<FlowEvent>> consumer) {
        traces.keySet().forEach(id -> consumer.accept(id, events(id)));
    }

    private static final class TraceBuffer {
        private final ConcurrentLinkedQueue<FlowEvent> events = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
    }
}
