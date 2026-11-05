-- LogSense schema (PostgreSQL). Flyway is the source of truth; Hibernate runs with ddl-auto=none.

CREATE TABLE analysis_job (
    id               UUID PRIMARY KEY,
    file_name        VARCHAR(512)  NOT NULL,
    file_size        BIGINT        NOT NULL DEFAULT 0,
    storage_path     VARCHAR(1024),
    status           VARCHAR(16)   NOT NULL,
    progress         INTEGER       NOT NULL DEFAULT 0,
    detected_format  VARCHAR(32),
    total_lines      BIGINT        NOT NULL DEFAULT 0,
    parsed_events    BIGINT        NOT NULL DEFAULT 0,
    unparsed_lines   BIGINT        NOT NULL DEFAULT 0,
    findings_count   INTEGER       NOT NULL DEFAULT 0,
    incidents_count  INTEGER       NOT NULL DEFAULT 0,
    trace_count      INTEGER       NOT NULL DEFAULT 0,
    error_message    VARCHAR(2000),
    created_at       TIMESTAMPTZ   NOT NULL,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    CONSTRAINT chk_job_status CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_job_progress CHECK (progress BETWEEN 0 AND 100)
);
CREATE INDEX idx_job_status_created ON analysis_job (status, created_at DESC);

-- allocationSize=50 in JPA => INCREMENT BY 50 enables pooled-optimizer batch inserts
CREATE SEQUENCE log_event_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE log_event (
    id               BIGINT PRIMARY KEY,
    job_id           UUID          NOT NULL REFERENCES analysis_job (id) ON DELETE CASCADE,
    line_number      BIGINT        NOT NULL,
    event_timestamp  TIMESTAMPTZ,
    service          VARCHAR(128),
    level            VARCHAR(16),
    thread           VARCHAR(128),
    trace_id         VARCHAR(64),
    correlation_id   VARCHAR(128),
    span_id          VARCHAR(64),
    parent_span_id   VARCHAR(64),
    message          VARCHAR(4000),
    exception_class  VARCHAR(256),
    stack_trace      TEXT,
    http_status      INTEGER,
    duration_ms      BIGINT,
    source_format    VARCHAR(32)
);
CREATE INDEX idx_log_event_trace ON log_event (trace_id);
CREATE INDEX idx_log_event_job_ts ON log_event (job_id, event_timestamp);
CREATE INDEX idx_log_event_service_level ON log_event (service, level);

CREATE TABLE incident (
    id                  UUID PRIMARY KEY,
    job_id              UUID          NOT NULL REFERENCES analysis_job (id) ON DELETE CASCADE,
    title               VARCHAR(512)  NOT NULL,
    root_cause_service  VARCHAR(128),
    root_cause_category VARCHAR(32),
    severity            VARCHAR(16)   NOT NULL,
    confidence          DOUBLE PRECISION NOT NULL,
    summary             TEXT,
    ai_summary          TEXT,
    causal_chain        VARCHAR(1024),
    affected_services   VARCHAR(1024),
    trace_ids           VARCHAR(2000),
    first_failure_at    TIMESTAMPTZ,
    last_failure_at     TIMESTAMPTZ,
    duration_ms         BIGINT        NOT NULL DEFAULT 0,
    finding_count       INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_incident_first_failure ON incident (first_failure_at);
CREATE INDEX idx_incident_root_service ON incident (root_cause_service);
CREATE INDEX idx_incident_severity ON incident (severity);
CREATE INDEX idx_incident_job ON incident (job_id);
CREATE INDEX idx_incident_created ON incident (created_at);

CREATE TABLE finding (
    id               UUID PRIMARY KEY,
    incident_id      UUID          NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    job_id           UUID          NOT NULL,
    category         VARCHAR(32)   NOT NULL,
    severity         VARCHAR(16)   NOT NULL,
    service          VARCHAR(128),
    target_service   VARCHAR(128),
    event_timestamp  TIMESTAMPTZ,
    trace_id         VARCHAR(64),
    line_number      BIGINT        NOT NULL DEFAULT 0,
    exception_class  VARCHAR(256),
    message          VARCHAR(4000),
    evidence         TEXT,
    detector         VARCHAR(64)
);
CREATE INDEX idx_finding_incident ON finding (incident_id);
CREATE INDEX idx_finding_job_category ON finding (job_id, category);
CREATE INDEX idx_finding_service ON finding (service);

CREATE TABLE incident_timeline (
    incident_id  UUID         NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    item_order   INTEGER      NOT NULL,
    occurred_at  TIMESTAMPTZ,
    offset_ms    BIGINT       NOT NULL DEFAULT 0,
    service      VARCHAR(128),
    category     VARCHAR(32),
    severity     VARCHAR(16),
    description  VARCHAR(1000),
    PRIMARY KEY (incident_id, item_order)
);

CREATE TABLE incident_recommendation (
    incident_id     UUID          NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    item_order      INTEGER       NOT NULL,
    recommendation  VARCHAR(1000),
    PRIMARY KEY (incident_id, item_order)
);

CREATE TABLE service_call_edge (
    id              UUID PRIMARY KEY,
    job_id          UUID          NOT NULL REFERENCES analysis_job (id) ON DELETE CASCADE,
    caller          VARCHAR(128)  NOT NULL,
    callee          VARCHAR(128)  NOT NULL,
    call_count      BIGINT        NOT NULL DEFAULT 0,
    error_count     BIGINT        NOT NULL DEFAULT 0,
    avg_latency_ms  DOUBLE PRECISION,
    max_latency_ms  BIGINT,
    CONSTRAINT uq_edge_job_caller_callee UNIQUE (job_id, caller, callee)
);