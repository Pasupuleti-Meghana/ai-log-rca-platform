package com.logsense.rca;

import com.logsense.domain.model.Finding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Groups time-ordered findings into incidents: a silence longer than {@code gap} starts a new incident. */
public final class IncidentClusterer {

    private IncidentClusterer() {
    }

    public static List<List<Finding>> cluster(List<Finding> findings, Duration gap) {
        List<Finding> sorted = findings.stream()
                .filter(f -> f.timestamp() != null)
                .sorted(Comparator.comparing(Finding::timestamp).thenComparingLong(Finding::lineNumber))
                .toList();
        List<List<Finding>> clusters = new ArrayList<>();
        List<Finding> current = new ArrayList<>();
        Finding previous = null;
        for (Finding f : sorted) {
            if (previous != null && Duration.between(previous.timestamp(), f.timestamp()).compareTo(gap) > 0) {
                clusters.add(current);
                current = new ArrayList<>();
            }
            current.add(f);
            previous = f;
        }
        if (!current.isEmpty()) {
            clusters.add(current);
        }
        return clusters;
    }
}
