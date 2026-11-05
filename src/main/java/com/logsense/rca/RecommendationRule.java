package com.logsense.rca;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.Finding;

import java.util.List;
import java.util.regex.Pattern;

/**
 * @param trigger optional pattern evaluated against finding text; {@code null} = generic rule for the category
 */
public record RecommendationRule(FailureCategory category, Pattern trigger, List<String> recommendations) {

    public boolean isGeneric() {
        return trigger == null;
    }

    public boolean appliesTo(Finding finding) {
        if (finding.category() != category) {
            return false;
        }
        if (trigger == null) {
            return true;
        }
        String text = String.join("\n", finding.evidence()) + "\n"
                + (finding.message() == null ? "" : finding.message()) + "\n"
                + (finding.exceptionClass() == null ? "" : finding.exceptionClass());
        return trigger.matcher(text).find();
    }
}
