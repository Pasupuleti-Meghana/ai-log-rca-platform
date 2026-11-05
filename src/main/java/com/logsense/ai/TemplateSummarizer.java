package com.logsense.ai;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;
import com.logsense.rca.RcaResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Deterministic, explainable summary built from the RCA result (no external calls, always available). */
@Component
public final class TemplateSummarizer implements AiSummarizer {

    private static final int MAX_EVIDENCE_CHARS = 180;

    @Override
    public String summarize(RcaResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.rootCauseService()).append(" is the most likely root cause (")
                .append(Math.round(r.confidence() * 100)).append("% confidence). ");
        sb.append("The first ").append(r.rootCauseCategory().displayName().toLowerCase(Locale.ROOT))
                .append(" was observed at ").append(r.firstFailureAt());
        if (r.rootEvidence() != null && !r.rootEvidence().isEmpty()) {
            sb.append(": \"").append(truncate(r.rootEvidence().getFirst())).append('"');
        }
        if (r.rootTargetDependency() != null) {
            sb.append(" while calling ").append(r.rootTargetDependency());
        }
        sb.append(". ");
        List<String> chain = r.causalChain() == null ? List.of() : r.causalChain();
        if (chain.size() > 1) {
            List<String> upstream = new ArrayList<>(chain);
            java.util.Collections.reverse(upstream);
            String symptoms = r.findings().stream()
                    .filter(f -> !f.service().equals(r.rootCauseService()))
                    .map(Finding::category)
                    .distinct()
                    .map(FailureCategory::displayName)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
            sb.append("The failure propagated upstream ").append(String.join(" -> ", upstream));
            if (!symptoms.isEmpty()) {
                sb.append(", surfacing as ").append(symptoms);
            }
            sb.append(". ");
        }
        sb.append(r.findings().size()).append(" finding(s) across ").append(r.affectedServices().size())
                .append(" service(s) over ").append(String.format(Locale.ROOT, "%.1f", r.durationMs() / 1000.0))
                .append("s.");
        if (r.recommendations() != null && !r.recommendations().isEmpty()) {
            sb.append(" Recommended first action: ").append(r.recommendations().getFirst());
        }
        return sb.toString();
    }

    @Override
    public boolean isAiBacked() {
        return false;
    }

    @Override
    public String name() {
        return "template";
    }

    private static String truncate(String s) {
        return s.length() <= MAX_EVIDENCE_CHARS ? s : s.substring(0, MAX_EVIDENCE_CHARS) + "...";
    }
}
