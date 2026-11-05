package com.logsense.processing.masking;

import com.logsense.domain.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personally identifiable information and secrets before log data is persisted or sent to an LLM:
 * e-mail addresses, payment card numbers (Luhn validated), JWT / bearer tokens and password / secret / api-key
 * assignments.
 */
@Component
public class PiiMasker {

    public static final String EMAIL_MASK = "[EMAIL]";
    public static final String CARD_MASK = "[CARD]";
    public static final String TOKEN_MASK = "[TOKEN]";
    public static final String SECRET_MASK = "[REDACTED]";

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|token|authorization)(\\s*[=:]\\s*)(\"?)(?!(?i:bearer)\\b)(?!\\[TOKEN\\])[^\\s\",;&]+");

    private final boolean enabled;

    public PiiMasker() {
        this(true);
    }

    public PiiMasker(boolean enabled) {
        this.enabled = enabled;
    }

    public String mask(String input) {
        if (!enabled || input == null || input.isEmpty()) {
            return input;
        }
        String out = JWT.matcher(input).replaceAll(TOKEN_MASK);
        out = BEARER.matcher(out).replaceAll("$1 " + TOKEN_MASK);
        out = SECRET_ASSIGNMENT.matcher(out).replaceAll("$1$2$3" + SECRET_MASK);
        out = EMAIL.matcher(out).replaceAll(EMAIL_MASK);
        out = maskCards(out);
        return out;
    }

    public List<String> mask(List<String> lines) {
        return lines.stream().map(this::mask).toList();
    }

    public ParsedLogEvent mask(ParsedLogEvent event) {
        if (!enabled) {
            return event;
        }
        return event.toBuilder()
                .message(mask(event.message()))
                .stackTrace(mask(event.stackTrace()))
                .build();
    }

    private static String maskCards(String text) {
        Matcher m = CARD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            boolean isCard = digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits);
            m.appendReplacement(sb, Matcher.quoteReplacement(isCard ? CARD_MASK : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
