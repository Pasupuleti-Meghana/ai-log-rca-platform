package com.logsense.ai;

import com.logsense.domain.model.Finding;
import com.logsense.processing.masking.PiiMasker;
import com.logsense.rca.RcaResult;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Builds a compact, PII-masked prompt from condensed findings. Raw logs are never sent to the LLM - only the
 * RCA result and a bounded number of masked, truncated finding messages.
 */
public final class PromptBuilder {

    static final String SYSTEM_PROMPT = "You are a senior site reliability engineer. Given condensed failure findings "
            + "from a distributed system, write a concise root cause analysis (max 6 sentences): what failed first, "
            + "how it propagated, customer impact and the most important remediation. Do not invent facts.";
    private static final int MAX_MESSAGE_CHARS = 200;

    private PromptBuilder() {
    }

    public static String userPrompt(RcaResult r, int maxFindings, PiiMasker masker) {
        String findings = r.findings().stream()
                .sorted(Comparator.comparing(Finding::severity).reversed().thenComparing(Finding::timestamp))
                .limit(maxFindings)
                .sorted(Comparator.comparing(Finding::timestamp))
                .map(f -> "- [" + f.timestamp() + "] " + f.service() + " " + f.category() + " (" + f.severity() + "): "
                        + truncate(masker.mask(f.message())))
                .collect(Collectors.joining("\n"));
        return """
                Detected root cause service: %s
                Root cause category: %s
                Confidence: %.2f
                Causal chain (symptom -> root): %s
                Affected services: %s
                Window: %s to %s
                Key findings:
                %s
                """.formatted(r.rootCauseService(), r.rootCauseCategory(), r.confidence(),
                String.join(" -> ", r.causalChain()), String.join(", ", r.affectedServices()),
                r.firstFailureAt(), r.lastFailureAt(), findings);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_MESSAGE_CHARS ? s : s.substring(0, MAX_MESSAGE_CHARS) + "...";
    }
}
