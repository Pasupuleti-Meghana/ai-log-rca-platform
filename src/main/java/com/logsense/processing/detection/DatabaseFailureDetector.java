package com.logsense.processing.detection;

import com.logsense.domain.model.FailureCategory;
import com.logsense.domain.model.LogLevel;
import com.logsense.domain.model.ParsedLogEvent;
import com.logsense.domain.model.Severity;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** SQL errors, connection-pool exhaustion (HikariCP), deadlocks, constraint violations. */
@Component
public class DatabaseFailureDetector extends AbstractPatternDetector {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(SQL(?:Transient|NonTransient|Recoverable)?(?:Connection)?Exception|PSQLException|JDBCConnectionException"
                    + "|DataAccessResourceFailureException|CannotGetJdbcConnectionException|CannotCreateTransactionException"
                    + "|HikariPool-\\d+ - Connection is not available|connection pool (?:is )?exhausted|pool exhausted"
                    + "|Unable to acquire JDBC Connection|deadlock|could not serialize access|LockAcquisitionException"
                    + "|lock wait timeout|ConstraintViolationException|DataIntegrityViolationException|duplicate key value"
                    + "|violates (?:foreign key|unique|not-null|check) constraint|too many connections|ORA-\\d{5}"
                    + "|database (?:is )?unavailable)");

    @Override
    public int order() {
        return 20;
    }

    @Override
    protected FailureCategory category() {
        return FailureCategory.DATABASE;
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected Severity severity(ParsedLogEvent event) {
        return effectiveLevel(event) == LogLevel.WARN ? Severity.HIGH : Severity.CRITICAL;
    }
}
