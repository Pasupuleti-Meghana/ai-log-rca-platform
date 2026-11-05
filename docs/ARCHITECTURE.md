# LogSense – Architecture

## 1. Context

Microservices log to separate files and formats. When an order fails, an engineer has to grep the gateway,
order, inventory and payment logs, line them up by trace ID and work out which service failed *first* and which
ones were just reacting. LogSense automates that:

**ingest → parse → correlate → detect → RCA → report**

## 2. Component diagram

```mermaid
flowchart LR
    subgraph Clients
        UI[Swagger UI / Postman / CI hook]
        KAFKA[(Kafka topic<br/>profile 'kafka')]
    end

    subgraph API["api (REST)"]
        JC[JobController]
        IC[IncidentController]
        DC[DashboardController]
        GEH[GlobalExceptionHandler<br/>RFC 7807]
    end

    subgraph Service["service"]
        JS[JobService]
        JD[JobDispatcher<br/>@TransactionalEventListener]
        JP[AnalysisJobProcessor]
        IS[IncidentService]
        RS[ReportService]
        TFS[TransactionFlowService]
        MET[LogSenseMetrics<br/>Micrometer]
    end

    subgraph Processing["processing"]
        PIPE[LogIngestionPipeline<br/>BufferedReader + chunks]
        ASM[LogEventAssembler<br/>multi-line]
        PF[LogParserFactory<br/>Strategy + auto-detect]
        PII[PiiMasker]
        DE[DetectionEngine<br/>Chain of Responsibility]
        TA[TraceAggregator<br/>ConcurrentHashMap]
        TFA[TransactionFlowAnalyzer]
    end

    subgraph RCA["rca"]
        RE[RcaEngine]
        CL[IncidentClusterer]
        REC[RecommendationEngine]
    end

    subgraph AI["ai"]
        TS[TemplateSummarizer]
        OAI[OpenAiCompatibleSummarizer<br/>logsense.ai.enabled=true]
    end

    subgraph Reporting["reporting"]
        PDF[PdfReportGenerator<br/>OpenPDF]
        JSON[JsonReportExporter]
        DASH[DashboardService]
    end

    DB[(PostgreSQL<br/>Flyway)]
    LLM[(OpenAI-compatible LLM<br/>OpenAI / Ollama)]

    UI --> JC & IC & DC
    KAFKA --> JS
    JC --> JS & IS & TFS
    IC --> IS & RS
    DC --> DASH
    JS -- JobSubmittedEvent --> JD --> JP
    JP --> PIPE --> ASM --> PF
    PIPE --> PII --> DE
    PIPE --> TA
    JP --> TFA
    JP --> RE --> CL & REC & TS
    JP -. optional .-> OAI --> LLM
    RS --> PDF & JSON
    JS & JP & IS & DASH --> DB
    PIPE --> DB
    JP --> MET
```

## 3. Sequence: upload → parse → detect → RCA → report

```mermaid
sequenceDiagram
    autonumber
    actor U as Client
    participant C as JobController
    participant S as JobService
    participant D as JobDispatcher
    participant P as AnalysisJobProcessor
    participant L as LogIngestionPipeline
    participant X as chunkExecutor (bounded)
    participant F as TransactionFlowAnalyzer
    participant R as RcaEngine
    participant A as AiSummarizer
    participant DB as PostgreSQL

    U->>C: POST /api/v1/jobs (multipart file)
    C->>S: submit(file)
    S->>S: validate extension/size, stream to upload dir
    S->>DB: INSERT analysis_job (QUEUED)
    S-->>D: JobSubmittedEvent (after commit)
    C-->>U: 202 Accepted + Location: /api/v1/jobs/{id}
    D->>P: jobExecutor.execute(process(jobId))
    P->>DB: status = PROCESSING
    P->>L: ingest(file stream)
    loop each line (BufferedReader)
        L->>L: assemble multi-line event, parse with auto-detected parser
        L->>X: every 1000 events: CompletableFuture chunk
        X->>X: mask PII → detectors → TraceAggregator
        X->>DB: saveAll(batch) (JDBC batch)
        L->>DB: progress % (throttled)
    end
    L-->>P: IngestionResult (findings, traces)
    P->>F: analyze each trace (parallel stream)
    F-->>P: call edges + missing responses
    P->>DB: INSERT service_call_edge
    P->>R: analyze(findings, call graph)
    R-->>P: List<RcaResult> (root, confidence, chain, timeline, recommendations)
    opt logsense.ai.enabled
        P->>A: summarize(condensed, masked findings)
    end
    P->>DB: INSERT incident + findings + timeline; job COMPLETED
    P-->>P: publish AnalysisCompletedEvent (metrics/logging)
    U->>C: GET /api/v1/jobs/{id} (poll)
    U->>C: GET /api/v1/incidents/{id}/report/pdf
```

## 4. Processing pipeline details

| Stage | Class | Notes |
|---|---|---|
| Streaming read | `LogIngestionPipeline` | `BufferedReader` with 64 KB buffer over a `CountingInputStream`; memory use is independent of file size. Progress = bytes read / file size (capped at 95% until RCA finishes). |
| Multi-line assembly | `LogEventAssembler` | A line is a continuation if it starts with whitespace, `at `, `Caused by:`, `Suppressed:`, `... n more`, or is a bare exception header. Continuations are appended to the previous event's stack trace. |
| Parsing | `LogParserFactory` + `LogParser` strategies | JSON (ECS/Logback, flat or nested keys) → Spring Boot → Log4j → access log → generic regex. The parser that matched the previous line is tried first (most files are homogeneous). Mixed files work because detection is per line. |
| Normalisation | `TimestampParser`, `KeyValueExtractor` | ISO-8601 (with/without offset, comma fractions), access-log, syslog and epoch → `Instant` UTC. `traceId=`, `spanId=`, `service=` etc. are pulled from messages. |
| Chunking | `chunkExecutor` | Batches of `batch-size` events run as `CompletableFuture`s on a bounded pool. **Backpressure:** queue capacity = 2×threads and `CallerRunsPolicy` makes the reader thread do the work itself when workers fall behind, so reading slows to processing speed instead of filling memory. |
| PII masking | `PiiMasker` | Emails, Luhn-valid card numbers, JWTs, bearer tokens, `password=`/`token=` values. Done **before** persistence, detection and LLM calls. |
| Detection | `DetectionEngine` | Chain of Responsibility, first match wins: retry exhaustion → database → timeout → communication → generic exception. |
| Correlation | `TraceAggregator` | `ConcurrentHashMap<traceId, Queue<FlowEvent>>` with caps on traces and events per trace (memory guard). |
| Persistence | `JpaEventSink` | `saveAll` per chunk in its own transaction; sequence ids with pooled allocation so Hibernate batches inserts. |

## 5. Transaction-flow reconstruction

For each trace (`TransactionFlowAnalyzer`):

1. Sort events by `(timestamp, lineNumber)` → timeline with `offsetMs` from the first event.
2. **Span edges:** for every span with a `parentSpanId`, if the parent span belongs to another service, add
   `parentService → service` (latency = span duration, error if any event in the span is an error).
3. **Message heuristics** (for logs without span propagation):
   * `calling X`, `routing request to X`, `forwarding to X`… → push a pending request `(caller, X)`.
   * `received response from X … status=NNN … in N ms` → close the oldest pending request, record latency and error (status ≥ 500).
   * WARN/ERROR in the caller that mentions a pending callee (e.g. `Read timed out calling inventory-service`) → close it as a failed call.
4. **Missing responses:** requests still pending at the end of the trace, or responses that arrived after
   `missing-response-timeout`, become `MISSING_RESPONSE` findings.

Edges from all traces are merged into a job-level `ServiceCallGraph` (call count, error count, avg/max latency).

## 6. RCA algorithm

### Pseudocode

```
analyze(findings, globalGraph):
    clusters = splitWhereGapBetweenConsecutiveFindings > incidentGap (5m)   // one cluster = one incident
    for cluster in clusters where any severity >= MEDIUM:
        byService     = groupBy(cluster, service)                 // insertion order = time order
        failing       = keys(byService)
        firstFailure  = { s -> min(timestamp of s's findings) }

        graph = copy(globalGraph)
        for f in cluster where f.targetService != null:           // "timed out calling inventory-service"
            graph.addEdge(f.service -> f.targetService, error)

        // 1. candidates = deepest failing components
        sinks = { s in failing | downstream(s) ∩ failing = ∅ }
        if sinks is empty: sinks = failing                        // cycle / no topology

        // 2. score each candidate
        for s in sinks:
            score = 0.35
            score += 0.20 if firstFailure[s] == earliest(all)  else
                     0.10 if firstFailure[s] == earliest(sinks) else 0
            score += 0.20 * weight(dominantCategory(s)) / 5       // DB 5, RETRY 4, COMM 3, TIMEOUT 2, other 1
            score += 0.20 * |{o in failing\{s} : s ∈ downstream(o)}| / |failing\{s}|   (1.0 if alone)
            score -= min(0.15, 0.05 * (|sinks| - 1))              // ambiguity penalty

        root       = argmax(score), tie → earlier failure → name
        confidence = round2(clamp(score[root], 0.05, 0.99))
        chain      = longest path of failing callers ending at root   // [api-gateway, order-service, inventory-service]
        dependency = first targetService of root that is not itself failing (e.g. fraud-check-service)
        timeline   = cluster findings, ≤3 per (service, category), offsets from first finding
        recs       = RecommendationEngine(rootCategory, rootFindings, cluster)
        summary    = TemplateSummarizer(result)        // + optional LLM summary
```

### Step by step on the cascade sample

| t (s) | service | finding | category |
|---|---|---|---|
| 0.9 | inventory-service | HikariPool-1 – Connection pool exhausted | DATABASE |
| 5.2 | order-service | Read timed out calling inventory-service | TIMEOUT → target inventory-service |
| 5.26 | api-gateway | 504 GATEWAY_TIMEOUT from order-service | TIMEOUT → target order-service |
| 30.2 | inventory-service | SQLTransientConnectionException | DATABASE |

1. One cluster (gaps < 5 min). `failing = {inventory, order, gateway}`.
2. Graph: `gateway → order → inventory` (from "routing to"/"calling" heuristics and finding targets).
3. Sinks: only `inventory-service` has no failing downstream service.
4. Score = 0.35 + 0.20 (earliest) + 0.20 × 5/5 (DATABASE) + 0.20 × 2/2 (both others reach it) = **0.95**.
5. Chain = `api-gateway → order-service → inventory-service`; recommendations start with HikariCP leak detection,
   slow-query analysis and pool sizing.

**Root vs symptom:** timeouts and 5xx upstream are *symptoms*: they point at a callee. Graph position (sink)
outweighs timestamps because log flush order is not causal order. A test covers the gateway logging its 504
before inventory logs its DB error, and inventory is still chosen (with lower confidence).

## 7. Design patterns

| Pattern | Where |
|---|---|
| Strategy | `LogParser` implementations; `FailureDetector` implementations; `AiSummarizer` |
| Factory | `LogParserFactory` (auto-detection, preferred parser) |
| Chain of Responsibility | `DetectionEngine` ordered detectors, first match wins |
| Template Method | `AbstractRegexLogParser.parse()` (match → map → enrich), `AbstractPatternDetector.detect()` |
| Builder | Lombok `@Builder` on `ParsedLogEvent`, `Finding`, `RcaResult` records |
| Observer | `JobSubmittedEvent` → `JobDispatcher` (after commit); `AnalysisCompletedEvent` → listener |
| Ports & adapters | `EventSink` (pipeline independent of JPA), `AiSummarizer` (sealed: template or LLM) |
| Specification | `IncidentSpecifications` for dynamic filtering |

## 8. Design decisions and trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| Store the upload on disk, process async with an in-process executor | No broker needed; 202 + polling is simple and robust | Jobs in flight are lost on crash. They stay `PROCESSING`; production needs a reaper or durable queue. |
| Streaming + chunked parallelism | Constant memory; uses all cores for regex-heavy detection | Chunks complete out of order, so per-trace ordering is restored by sorting `(timestamp, line)`. |
| `CallerRunsPolicy` for backpressure | Simplest bounded design, no dropped data | The reader thread sometimes does detection work, so read throughput varies. |
| Deterministic RCA + optional LLM | Explainable, testable, free, works offline; LLM only rephrases | Heuristics need tuning per organisation (weights in `FailureCategory`). |
| PII masked before storage | Privacy by default (GDPR); LLM never sees raw logs | Masked data can't be unmasked for debugging. |
| Postgres for events | One datastore, transactional with incidents | Not suitable for TB-scale full-text search (see §9). |
| Sequence ids (`allocationSize=50`) | JDBC batch inserts | Id gaps (harmless). |
| Trace / event caps in memory | Protects the heap on huge files | Very long traces are truncated for flow analysis (all events are still persisted). |
| Records + sealed `AiSummarizer` | Immutability, exhaustive implementations | Lombok builders on records add a little magic. |

## 9. SDE-2 interview talking points

**Scaling to TB of logs.** A single JVM streaming at ~100–200k lines/s is fine up to tens of GB per job. Beyond
that, split ingestion from analysis:
- Ship logs with Fluent Bit / Vector into **Kafka**, partitioned by `traceId`. All events of a transaction then
  land on one partition, which keeps ordering and lets a consumer correlate without cross-node shuffles.
- Stateless consumer group: parse → mask → detect → write findings. Trace state lives in a windowed state store
  (Kafka Streams / Flink, session window keyed by traceId) instead of a `ConcurrentHashMap`.
- RCA runs on findings, which are orders of magnitude smaller than raw logs, so it stays cheap.

**Kafka ingestion (implemented behind the `kafka` profile).** A batch `@KafkaListener` writes each poll batch to the upload
dir and reuses the file pipeline. For production: manual acks after the job commits, a DLQ for poison
messages, and idempotent producers.

**Partitioning by traceId.** Hash partitioning gives an even spread, ordering per trace, and locality for
correlation. Hot traces are rare. Hot *services* are not a problem because the key is the trace, not the service.

**Elasticsearch / ClickHouse vs Postgres.**
- Postgres (here): transactional, simple, good for incidents and findings, OK for millions of events per job with the
  `(job_id, ts)` and `trace_id` indexes. Partition `log_event` by time and drop old partitions for retention.
- Elasticsearch/OpenSearch: full-text search over messages, Kibana. Costly at scale and eventually consistent.
- ClickHouse: columnar, 10–20× compression, very fast aggregations (errors per service per minute). The best fit
  for raw events at TB scale; keep Postgres for incidents and metadata.

**Backpressure.**
- Bounded queues everywhere: the job executor (AbortPolicy → job marked FAILED, client can retry) and the chunk
  executor (CallerRunsPolicy).
- Kafka gives backpressure for free through consumer lag.
- Upload size is capped at 500 MB, and the multipart threshold spills to disk.

**Idempotency.** Today each upload is a new job. Production version:
- Content hash (SHA-256 while streaming) plus an `Idempotency-Key` header; return the existing job for duplicates.
- Kafka consumers dedupe on `(topic, partition, offset)` or event hash.
- `UNIQUE(job_id, caller, callee)` already makes the edge writes idempotent.

**Other points.**
- Graceful shutdown drains executors.
- Metrics: `logsense.logs.parsed`, `logsense.incidents.detected{category}`, `logsense.job.processing.time`,
  `logsense.jobs.active`.
- RFC 7807 errors.
- The confidence score is explainable: each component is shown above.