# LogSense – API Specification

* Base URL: `http://localhost:8080`, all endpoints are versioned under `/api/v1`.
* OpenAPI: `GET /v3/api-docs`. Swagger UI: `/swagger-ui.html`.
* Errors: **RFC 7807** `application/problem+json`. Every error has `type`, `title`, `status`, `detail`, `instance` and `timestamp`. Validation errors also carry `errors`.
* Timestamps: ISO-8601 UTC (`2024-10-15T10:00:00.900Z`).

## Jobs

### `POST /api/v1/jobs` – upload a log file
`multipart/form-data` with a part named `file`.
- Allowed extensions: `.log .txt .json .jsonl .out`.
- Maximum size: 500 MB.

```bash
curl -F "file=@samples/cascade-db-pool-exhaustion.log" http://localhost:8080/api/v1/jobs
```

`202 Accepted`, with header `Location: /api/v1/jobs/{id}`:

```json
{
  "id": "4f1d3c2e-...", "fileName": "cascade-db-pool-exhaustion.log", "fileSize": 7012,
  "status": "QUEUED", "progress": 0, "detectedFormat": null,
  "totalLines": 0, "parsedEvents": 0, "unparsedLines": 0,
  "findingsCount": 0, "incidentsCount": 0, "traceCount": 0,
  "errorMessage": null, "createdAt": "2024-10-15T10:05:00Z", "startedAt": null, "completedAt": null,
  "processingTimeMs": null
}
```

| Status | When |
|---|---|
| 400 | missing `file` part, empty file, unsupported extension |
| 413 | file larger than `spring.servlet.multipart.max-file-size` |
| 202 then `FAILED` | job queue full: the bounded executor rejected the job, and it is marked FAILED |

### `POST /api/v1/jobs/text` – analyse raw text
Use this for snippets; use the multipart upload for files.

```json
{ "sourceName": "billing-service.log", "content": "2024-10-15 10:00:00,000 [main] ERROR ... SQLException ..." }
```

Validation:
- `sourceName`: required, max 255 characters, only `[A-Za-z0-9_.-]`.
- `content`: required, max 5 MB.

Response: `202`, same body as the upload.
On validation failure: `400` with `errors: {"field": "message"}`.

### `GET /api/v1/jobs/{jobId}` – status & progress
`status` is one of `QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`. `progress` is 0–100.
`detectedFormat` is one of `JSON`, `SPRING_BOOT`, `LOG4J`, `ACCESS_LOG`, `GENERIC`, `MIXED`.
Unknown id → `404`. Malformed UUID → `400`.

### `GET /api/v1/jobs?page=0&size=20` – list jobs (newest first)
`size` must be 1..200. The response is `PageResponse`:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 3, "totalPages": 1, "sort": "createdAt: DESC" }
```

### `GET /api/v1/jobs/{jobId}/incidents`
Returns the job's incidents as `IncidentSummaryDto`, ordered by first failure.

### `GET /api/v1/jobs/{jobId}/service-graph`
```json
[
  { "caller": "api-gateway", "callee": "order-service", "callCount": 3, "errorCount": 2,
    "avgLatencyMs": 85.0, "maxLatencyMs": 85, "errorRate": 0.67 },
  { "caller": "order-service", "callee": "inventory-service", "callCount": 3, "errorCount": 2,
    "avgLatencyMs": 45.0, "maxLatencyMs": 45, "errorRate": 0.67 }
]
```

### `GET /api/v1/jobs/{jobId}/traces/{traceId}` – transaction flow
```json
{
  "traceId": "7c2a8e4f1d9b3c50",
  "start": "2024-10-15T10:00:00.100Z", "end": "2024-10-15T10:00:30.210Z", "durationMs": 30110,
  "services": ["api-gateway", "order-service", "inventory-service"],
  "timeline": [
    { "timestamp": "2024-10-15T10:00:00.100Z", "offsetMs": 0, "service": "api-gateway", "level": "INFO",
      "spanId": "d4e5f60718293a4b", "message": "Routing request to order-service POST /api/orders" }
  ],
  "edges": [ { "caller": "order-service", "callee": "inventory-service", "callCount": 1, "errorCount": 1, ... } ],
  "missingResponses": [],
  "failed": true
}
```
If no events exist for the trace, the response is `404`.

## Incidents

### `GET /api/v1/incidents` – incident history
| Param | Type | Notes |
|---|---|---|
| `service` | string | matches the root-cause service **or** any finding's service |
| `severity` | `LOW,MEDIUM,HIGH,CRITICAL` | invalid value → 400 |
| `category` | `EXCEPTION,TIMEOUT,DATABASE,COMMUNICATION,RETRY_EXHAUSTION,MISSING_RESPONSE` | root-cause category |
| `jobId` | UUID | |
| `from`, `to` | ISO instant | filter on `firstFailureAt`; `from > to` → 400 |
| `page` | int ≥ 0 | default 0 |
| `size` | 1..200 | default 20 |
| `sort` | `property[,asc\|desc]` | whitelist: `firstFailureAt, createdAt, severity, confidence, rootCauseService, durationMs, findingCount`; default `firstFailureAt,desc` |

### `GET /api/v1/incidents/{id}` – full RCA
```json
{
  "id": "…", "jobId": "…",
  "title": "Database failure in inventory-service impacting 3 service(s)",
  "rootCauseService": "inventory-service", "rootCauseCategory": "DATABASE",
  "severity": "CRITICAL", "confidence": 0.95,
  "summary": "inventory-service is the most likely root cause (95% confidence). The first database failure was observed at 2024-10-15T10:00:00.900Z: \"HikariPool-1 - Connection pool exhausted ...\". The failure propagated upstream inventory-service -> order-service -> api-gateway, surfacing as timeout. ...",
  "aiSummary": null,
  "causalChain": ["api-gateway", "order-service", "inventory-service"],
  "affectedServices": ["inventory-service", "order-service", "api-gateway"],
  "traceIds": ["7c2a8e4f1d9b3c50", "9e4b2d6a8c1f3e70"],
  "firstFailureAt": "2024-10-15T10:00:00.900Z", "lastFailureAt": "2024-10-15T10:00:31.250Z",
  "durationMs": 30350, "findingCount": 8,
  "timeline": [ { "occurredAt": "...", "offsetMs": 0, "service": "inventory-service", "category": "DATABASE",
                  "severity": "HIGH", "description": "HikariPool-1 - Connection pool exhausted (...)" } ],
  "findings": [ { "category": "TIMEOUT", "service": "order-service", "targetService": "inventory-service",
                  "exceptionClass": "java.net.SocketTimeoutException", "evidence": ["..."], "detector": "TimeoutDetector", ... } ],
  "recommendations": ["Check for connection leaks: enable HikariCP leakDetectionThreshold ...", "..."],
  "createdAt": "..."
}
```

### `GET /api/v1/incidents/{id}/report/pdf`
Returns `application/pdf` as an attachment (`rca-{id}.pdf`). The report contains:
- overview table
- root-cause summary and the optional AI summary
- recommendations
- timeline
- findings (top 50)
- trace IDs

### `GET /api/v1/incidents/{id}/report/json`
Returns `application/json` as an attachment, wrapped in an envelope:

```json
{ "reportVersion": "1.0", "generatedAt": "...", "generator": "LogSense", "incident": { ...IncidentDetailDto... } }
```

## Dashboard

### `GET /api/v1/dashboard?from=&to=`
```json
{
  "totalJobs": 4, "jobsByStatus": { "QUEUED": 0, "PROCESSING": 0, "COMPLETED": 4, "FAILED": 0 },
  "totalIncidents": 3,
  "incidentsByCategory": { "DATABASE": 2, "RETRY_EXHAUSTION": 1 },
  "incidentsBySeverity": { "CRITICAL": 1, "HIGH": 2 },
  "incidentsByRootCauseService": { "inventory-service": 1, "order-service": 1, "payment-service": 1 },
  "topFailingServices": [ { "service": "inventory-service", "rootCauseCount": 1, "affectedCount": 1 } ],
  "averageIncidentDurationMs": 11000.0, "maxIncidentDurationMs": 30350,
  "averageConfidence": 0.87,
  "recentIncidents": [ ... ]
}
```
`averageIncidentDurationMs` is an MTTR-like metric: the time from an incident's first failure signal to its last.

## Error example

```json
{
  "type": "https://logsense.dev/problems/not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "Incident with id '00000000-0000-0000-0000-000000000000' was not found",
  "instance": "/api/v1/incidents/00000000-0000-0000-0000-000000000000",
  "timestamp": "2024-10-15T10:05:00Z"
}
```

| type suffix | status |
|---|---|
| `not-found` | 404 |
| `invalid-request` | 400 (bad file, bad sort, bad range) |
| `validation-error` | 400 (`errors` list/map) |
| `storage-error`, `report-generation`, `internal-error` | 500 |

## Operational endpoints
- `/actuator/health` (with liveness/readiness probes)
- `/actuator/metrics`
- `/actuator/prometheus`

Custom metrics:
- `logsense_logs_parsed_total`
- `logsense_findings_detected_total`
- `logsense_incidents_detected_total{category}`
- `logsense_job_processing_time_seconds`
- `logsense_jobs_active`
- `logsense_jobs_finished_total{status}`