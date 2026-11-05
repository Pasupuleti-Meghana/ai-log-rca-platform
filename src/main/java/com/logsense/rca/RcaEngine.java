package com.logsense.rca;

import com.logsense.ai.TemplateSummarizer;
import com.logsense.config.LogSenseProperties;
import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.domain.model.Severity;
import com.logsense.processing.flow.ServiceCallGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Root cause analysis.
 * <pre>
 * for each incident cluster (findings separated by &lt; incidentGap):
 *   failing   = services with findings
 *   graph     = call graph (spans + message heuristics) + edges implied by findings ("timed out calling X")
 *   sinks     = failing services with NO failing service downstream of them   (deepest failing components)
 *   score(s)  = 0.35 base
 *             + 0.20 if s failed first overall, 0.10 if first among sinks
 *             + 0.20 * categoryWeight(s) / 5            (DB &gt; retries &gt; network &gt; timeout &gt; exception)
 *             + 0.20 * share of other failing services that can reach s in the graph
 *             - 0.05 per competing sink (max 0.15)
 *   root      = argmax score (tie: earliest failure); confidence = clamp(score)
 *   chain     = longest failing caller path ending at root (symptoms -&gt; root)
 * </pre>
 */
@Component
public class RcaEngine {

    private static final int MAX_TIMELINE_ITEMS = 100;
    private static final int MAX_PER_SERVICE_CATEGORY = 3;
    private static final int MAX_TRACE_IDS = 20;

    private final RecommendationEngine recommendationEngine;
    private final TemplateSummarizer templateSummarizer;
    private final Duration incidentGap;

    @Autowired
    public RcaEngine(RecommendationEngine recommendationEngine, TemplateSummarizer templateSummarizer,
                     LogSenseProperties properties) {
        this(recommendationEngine, templateSummarizer, properties.processing().incidentGap());
    }

    public RcaEngine(RecommendationEngine recommendationEngine, TemplateSummarizer templateSummarizer,
                     Duration incidentGap) {
        this.recommendationEngine = recommendationEngine;
        this.templateSummarizer = templateSummarizer;
        this.incidentGap = incidentGap;
    }

    public List<RcaResult> analyze(List<Finding> findings, ServiceCallGraph graph) {
        return IncidentClusterer.cluster(findings, incidentGap).stream()
                .filter(c -> c.stream().anyMatch(f -> f.severity().compareTo(Severity.MEDIUM) >= 0))
                .map(c -> analyzeCluster(c, graph))
                .toList();
    }

    RcaResult analyzeCluster(List<Finding> cluster, ServiceCallGraph globalGraph) {
        Map<String, List<Finding>> byService = cluster.stream()
                .collect(Collectors.groupingBy(Finding::service, LinkedHashMap::new, Collectors.toList()));
        Set<String> failing = byService.keySet();
        Map<String, Instant> firstFailure = new HashMap<>();
        byService.forEach((s, fs) -> firstFailure.put(s, fs.getFirst().timestamp()));

        ServiceCallGraph graph = new ServiceCallGraph();
        graph.merge(globalGraph);
        cluster.stream()
                .filter(f -> f.targetService() != null)
                .forEach(f -> graph.addCall(f.service(), f.targetService(), null, true));

        List<String> sinks = failing.stream()
                .filter(s -> graph.downstreamOf(s).stream().noneMatch(failing::contains))
                .toList();
        if (sinks.isEmpty()) {
            sinks = List.copyOf(failing);
        }
        Instant earliestOverall = firstFailure.values().stream().min(Comparator.naturalOrder()).orElseThrow();
        Instant earliestSink = sinks.stream().map(firstFailure::get).min(Comparator.naturalOrder()).orElseThrow();

        Map<String, Double> scores = new HashMap<>();
        for (String s : sinks) {
            double score = 0.35;
            Instant first = firstFailure.get(s);
            if (first.equals(earliestOverall)) {
                score += 0.20;
            } else if (first.equals(earliestSink)) {
                score += 0.10;
            }
            score += 0.20 * dominantCategory(byService.get(s)).rootCauseWeight() / FailureCategory.maxWeight();
            score += 0.20 * reachShare(s, failing, graph);
            score -= Math.min(0.15, 0.05 * (sinks.size() - 1));
            scores.put(s, score);
        }
        String root = sinks.stream()
                .max(Comparator.<String>comparingDouble(scores::get)
                        .thenComparing(firstFailure::get, Comparator.reverseOrder())
                        .thenComparing(Comparator.reverseOrder()))
                .orElseThrow();

        List<Finding> rootFindings = byService.get(root);
        FailureCategory rootCategory = dominantCategory(rootFindings);
        Finding representative = rootFindings.stream()
                .filter(f -> f.category() == rootCategory).findFirst().orElse(rootFindings.getFirst());
        String rootTarget = rootFindings.stream()
                .map(Finding::targetService)
                .filter(Objects::nonNull)
                .filter(t -> !failing.contains(t))
                .findFirst().orElse(null);

        Instant first = cluster.getFirst().timestamp();
        Instant last = cluster.getLast().timestamp();
        List<String> affected = failing.stream().sorted(Comparator.comparing(firstFailure::get)).toList();

        RcaResult result = RcaResult.builder()
                .title(rootCategory.displayName() + " in " + root + " impacting " + affected.size() + " service(s)")
                .rootCauseService(root)
                .rootCauseCategory(rootCategory)
                .confidence(Math.round(Math.max(0.05, Math.min(0.99, scores.get(root))) * 100) / 100.0)
                .severity(cluster.stream().map(Finding::severity).reduce(Severity.LOW, Severity::max))
                .causalChain(causalChain(root, failing, graph))
                .affectedServices(affected)
                .firstFailureAt(first)
                .lastFailureAt(last)
                .durationMs(Duration.between(first, last).toMillis())
                .findings(List.copyOf(cluster))
                .timeline(timeline(cluster, root, first))
                .recommendations(recommendationEngine.recommend(rootCategory, rootFindings, cluster))
                .rootEvidence(representative.evidence())
                .traceIds(cluster.stream().map(Finding::traceId).filter(Objects::nonNull).distinct()
                        .limit(MAX_TRACE_IDS).toList())
                .rootTargetDependency(rootTarget)
                .build();
        return result.toBuilder().summary(templateSummarizer.summarize(result)).build();
    }

    static FailureCategory dominantCategory(List<Finding> findings) {
        return findings.stream()
                .max(Comparator.comparingInt((Finding f) -> f.category().rootCauseWeight())
                        .thenComparing(Finding::timestamp, Comparator.reverseOrder()))
                .map(Finding::category)
                .orElse(FailureCategory.EXCEPTION);
    }

    private static double reachShare(String candidate, Set<String> failing, ServiceCallGraph graph) {
        List<String> others = failing.stream().filter(s -> !s.equals(candidate)).toList();
        if (others.isEmpty()) {
            return 1.0;
        }
        long reaching = others.stream().filter(o -> graph.downstreamOf(o).contains(candidate)).count();
        return (double) reaching / others.size();
    }

    /** Longest path of failing callers ending at the root, ordered from outermost symptom to the root. */
    static List<String> causalChain(String root, Set<String> failing, ServiceCallGraph graph) {
        List<String> chain = longestUpstream(root, failing, graph, new HashSet<>());
        return List.copyOf(chain);
    }

    private static List<String> longestUpstream(String node, Set<String> failing, ServiceCallGraph graph,
                                                Set<String> visiting) {
        visiting.add(node);
        List<String> best = List.of();
        for (String caller : graph.callers(node).stream().sorted().toList()) {
            if (failing.contains(caller) && !visiting.contains(caller)) {
                List<String> candidate = longestUpstream(caller, failing, graph, visiting);
                if (candidate.size() > best.size()) {
                    best = candidate;
                }
            }
        }
        visiting.remove(node);
        List<String> path = new ArrayList<>(best);
        path.add(node);
        return path;
    }

    private static List<TimelineItem> timeline(List<Finding> cluster, String root, Instant start) {
        Map<String, Integer> perGroup = new HashMap<>();
        List<TimelineItem> items = new ArrayList<>();
        for (Finding f : cluster) {
            String group = f.service() + "|" + f.category();
            if (perGroup.merge(group, 1, Integer::sum) > MAX_PER_SERVICE_CATEGORY) {
                continue;
            }
            String description = f.message() == null ? f.category().displayName() : f.message();
            if (description.length() > 300) {
                description = description.substring(0, 300) + "...";
            }
            items.add(new TimelineItem(f.timestamp(), Duration.between(start, f.timestamp()).toMillis(), f.service(),
                    f.category(), f.severity(), description, f.service().equals(root)));
            if (items.size() >= MAX_TIMELINE_ITEMS) {
                break;
            }
        }
        return items;
    }
}
