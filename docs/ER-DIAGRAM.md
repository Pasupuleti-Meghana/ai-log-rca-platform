# LogSense – Entity Relationship Diagram

The schema is owned by Flyway (`src/main/resources/db/migration/V1__init.sql`). Hibernate runs with
`ddl-auto=none` in production; tests use H2 (`MODE=PostgreSQL`) with `create-drop`.

```mermaid
erDiagram
    ANALYSIS_JOB ||--o{ LOG_EVENT : "contains"
    ANALYSIS_JOB ||--o{ INCIDENT : "produces"
    ANALYSIS_JOB ||--o{ SERVICE_CALL_EDGE : "discovers"
    INCIDENT ||--o{ FINDING : "is evidenced by"
    INCIDENT ||--o{ INCIDENT_TIMELINE : "has ordered"
    INCIDENT ||--o{ INCIDENT_RECOMMENDATION : "has ordered"

    ANALYSIS_JOB {
        uuid id PK
        varchar file_name
        bigint file_size
        varchar storage_path
        varchar status "QUEUED|PROCESSING|COMPLETED|FAILED"
        int progress "0..100"
        varchar detected_format "JSON|SPRING_BOOT|LOG4J|ACCESS_LOG|GENERIC|MIXED"
        bigint total_lines
        bigint parsed_events
        bigint unparsed_lines
        int findings_count
        int incidents_count
        int trace_count
        varchar error_message
        timestamptz created_at
        timestamptz started_at
        timestamptz completed_at
    }
    LOG_EVENT {
        bigint id PK "sequence log_event_seq, increment 50"
        uuid job_id FK
        bigint line_number
        timestamptz event_timestamp
        varchar service
        varchar level
        varchar thread
        varchar trace_id
        varchar correlation_id
        varchar span_id
        varchar parent_span_id
        varchar message "PII masked"
        varchar exception_class
        text stack_trace "PII masked, capped 8000 chars"
        int http_status
        bigint duration_ms
        varchar source_format
    }
    INCIDENT {
        uuid id PK
        uuid job_id FK
        varchar title
        varchar root_cause_service
        varchar root_cause_category
        varchar severity
        double confidence "0.05..0.99"
        text summary "deterministic template"
        text ai_summary "optional LLM"
        varchar causal_chain "symptom -> root"
        varchar affected_services
        varchar trace_ids
        timestamptz first_failure_at
        timestamptz last_failure_at
        bigint duration_ms
        int finding_count
        timestamptz created_at
    }
    FINDING {
        uuid id PK
        uuid incident_id FK
        uuid job_id
        varchar category "EXCEPTION|TIMEOUT|DATABASE|COMMUNICATION|RETRY_EXHAUSTION|MISSING_RESPONSE"
        varchar severity
        varchar service
        varchar target_service
        timestamptz event_timestamp
        varchar trace_id
        bigint line_number
        varchar exception_class
        varchar message
        text evidence
        varchar detector
    }
    INCIDENT_TIMELINE {
        uuid incident_id PK, FK
        int item_order PK
        timestamptz occurred_at
        bigint offset_ms
        varchar service
        varchar category
        varchar severity
        varchar description
    }
    INCIDENT_RECOMMENDATION {
        uuid incident_id PK, FK
        int item_order PK
        varchar recommendation
    }
    SERVICE_CALL_EDGE {
        uuid id PK
        uuid job_id FK
        varchar caller "UNIQUE(job_id, caller, callee)"
        varchar callee
        bigint call_count
        bigint error_count
        double avg_latency_ms
        bigint max_latency_ms
    }
```

## Indexes

| Index | Purpose |
|---|---|
| `idx_log_event_trace (trace_id)` | transaction-flow reconstruction for one trace |
| `idx_log_event_job_ts (job_id, event_timestamp)` | time-ordered scans within a job |
| `idx_log_event_service_level (service, level)` | "all ERRORs of order-service" style queries |
| `idx_incident_first_failure`, `idx_incident_root_service`, `idx_incident_severity`, `idx_incident_job`, `idx_incident_created` | incident history filtering / sorting |
| `idx_finding_incident`, `idx_finding_job_category`, `idx_finding_service` | evidence lookup and the `service` filter (EXISTS sub-query) |
| `idx_job_status_created` | job listing / dashboard counts |

## Notes

* `log_event.id` uses a sequence with `INCREMENT BY 50` matching JPA `allocationSize=50` (pooled optimizer), so
  Hibernate can JDBC-batch inserts (`hibernate.jdbc.batch_size=1000`, `reWriteBatchedInserts=true`). `IDENTITY`
  would disable batching.
* All child tables use `ON DELETE CASCADE` so deleting a job removes its data in one statement (retention job).
* At larger scale `log_event` should be range-partitioned by `event_timestamp` (or moved to ClickHouse/Elasticsearch);
  see ARCHITECTURE.md.