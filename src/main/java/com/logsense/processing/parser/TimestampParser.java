package com.logsense.processing.parser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalizes the many timestamp flavours found in logs to {@link Instant} (UTC).
 * Supported: ISO-8601 (with/without offset, 'T' or space, '.' or ',' fractions), Apache/Nginx
 * {@code dd/MMM/yyyy:HH:mm:ss Z}, syslog {@code MMM d HH:mm:ss}, epoch seconds/millis.
 */
public final class TimestampParser {

    private static final Pattern EPOCH = Pattern.compile("^\\d{10}(\\d{3})?(\\.\\d+)?$");
    private static final Pattern OFFSET_NO_COLON = Pattern.compile("([+-]\\d{2})(\\d{2})$");
    private static final DateTimeFormatter ACCESS_LOG =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
    private static final DateTimeFormatter SYSLOG = new DateTimeFormatterBuilder()
            .appendPattern("MMM d HH:mm:ss")
            .parseDefaulting(ChronoField.YEAR, Year.now(ZoneOffset.UTC).getValue())
            .toFormatter(Locale.ENGLISH);

    private TimestampParser() {
    }

    public static Optional<Instant> parse(String raw) {
        return parse(raw, ZoneOffset.UTC);
    }

    public static Optional<Instant> parse(String raw, ZoneId defaultZone) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            if (EPOCH.matcher(value).matches()) {
                return Optional.of(parseEpoch(value));
            }
            if (value.indexOf('/') == 2) {
                return Optional.of(ZonedDateTime.parse(value, ACCESS_LOG).toInstant());
            }
            if (Character.isLetter(value.charAt(0))) {
                return Optional.of(LocalDateTime.parse(value.replaceAll("\\s+", " "), SYSLOG)
                        .atZone(defaultZone).toInstant());
            }
            return Optional.of(parseIsoLike(value, defaultZone));
        } catch (DateTimeParseException | NumberFormatException | StringIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private static Instant parseEpoch(String value) {
        if (value.contains(".")) {
            double seconds = Double.parseDouble(value);
            return Instant.ofEpochMilli((long) (seconds * 1000));
        }
        long n = Long.parseLong(value);
        return value.length() == 13 ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
    }

    private static Instant parseIsoLike(String value, ZoneId defaultZone) {
        String v = value.replace(',', '.');
        if (v.length() > 10 && v.charAt(10) == ' ') {
            v = v.substring(0, 10) + 'T' + v.substring(11);
        }
        v = v.replaceAll("\\s*(UTC|GMT)$", "Z").replaceAll("\\s+(?=[+-]\\d{2}:?\\d{2}$)", "");
        v = OFFSET_NO_COLON.matcher(v).replaceFirst("$1:$2");
        boolean hasOffset = v.endsWith("Z") || v.matches(".*[+-]\\d{2}:\\d{2}$");
        if (hasOffset) {
            return OffsetDateTime.parse(v, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        }
        return LocalDateTime.parse(v, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(defaultZone).toInstant();
    }
}
