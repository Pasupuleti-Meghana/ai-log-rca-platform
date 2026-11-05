# LogSense – File Guide

## Root
| File | Purpose |
|---|---|
| `pom.xml` | Maven build: Spring Boot 3.3.5, Java 21, JPA, Flyway, springdoc, Micrometer Prometheus, OpenPDF, spring-kafka, Lombok; tests: H2, Awaitility, Testcontainers |
| `Dockerfile` | Multi-stage build (Maven → JRE 21 alpine, non-root user, healthcheck) |
| `docker-compose.yml` | PostgreSQL 16 + app; optional Ollama (commented) |
| `.github/workflows/ci.yml` | CI: build, test, upload reports, build Docker image |
| `.gitignore` | Ignores build output, IDE files, logs (except `samples/`) |
| `README.md` | Overview, getting started, walkthrough, tests, project structure |
| `docs/` | ARCHITECTURE, ER-DIAGRAM, API, DEPLOYMENT and this guide |
| `postman/LogSense.postman_collection.json` | Postman v2.1 collection (`baseUrl`, auto-captured `jobId`/`incidentId`) |
| `samples/` | Realistic multi-service logs (see README walkthrough) |
| `tools/generate-large-log.ps1` / `.sh` | Wrapper that runs `LogGenerator` to create a 1M-line file |

## `src/main/java/com/logsense`

### Root
| File | Purpose |
|---|---|
| `LogSenseApplication.java` | Spring Boot entry point; `@ConfigurationPropertiesScan` |

### `api` – REST controllers (thin: validation + HTTP mapping only)
| File | Purpose |
|---|---|
| `JobController.java` | Upload (multipart), text analysis, job status, job list, job incidents, service graph, trace flow |
| `IncidentController.java` | Incident search (filters, paging, sort whitelist), detail, PDF and JSON report download |
| `DashboardController.java` | RCA dashboard aggregates |

### `config`
| File | Purpose |
|---|---|
| `LogSenseProperties.java` | Type-safe `logsense.*` config record with null-safe defaults (storage, processing, jobs, ai) |
| `AsyncConfig.java` | Bounded `jobExecutor` (AbortPolicy) and `chunkExecutor` (CallerRunsPolicy = backpressure); graceful drain |
| `AiConfig.java` | Registers `OpenAiCompatibleSummarizer` (RestClient with timeouts) only when `logsense.ai.enabled=true` |
| `OpenApiConfig.java` | OpenAPI metadata for springdoc |

### `domain.model` – in-memory domain (records/enums)
| File | Purpose |
|---|---|
| `ParsedLogEvent.java` | Normalised log event (builder/with); continuation handling, exception-class extraction, searchable text |
| `Finding.java` | Failure signal from a detector (category, severity, service, target service, evidence) |
| `FlowEvent.java` | Compact per-trace projection kept in memory for flow reconstruction |
| `FailureCategory.java` | Categories with root-cause weights used by RCA scoring |
| `Severity.java`, `LogLevel.java`, `JobStatus.java` | Enums with helpers (`max`, `isError`, `isTerminal`) |

### `domain.entity` – JPA entities
| File | Purpose |
|---|---|
| `AnalysisJob.java` | Job lifecycle, counters, progress; state transitions `markProcessing/Completed/Failed` |
| `LogEventEntity.java` | Persisted log event (sequence id, pooled allocation for batch inserts) |
| `IncidentEntity.java` | RCA result; findings (OneToMany), ordered timeline and recommendations (ElementCollection) |
| `FindingEntity.java` | Persisted finding linked to an incident |
| `TimelineEntry.java` | Embeddable timeline item |
| `ServiceCallEdgeEntity.java` | Aggregated caller→callee edge per job |
| `StringListConverter.java` | Stores small string lists as a delimited column |

### `processing.parser` – parsing (Strategy + Factory + Template Method)
| File | Purpose |
|---|---|
| `LogParser.java` | Strategy interface (`supports`, `parse`, `priority`, `format`) |
| `AbstractRegexLogParser.java` | Template Method: regex match → `map` → key/value enrichment and defaults |
| `JsonLogParser.java` | JSON lines (Logback JSON / ECS, flat or nested keys, stack traces, HTTP status) |
| `SpringBootLogParser.java` | Spring Boot default pattern incl. Boot 3 `[app] [thread] [trace,span]` brackets |
| `Log4jLogParser.java` | Log4j/Logback pattern layouts (`[thread] LEVEL logger - msg` and `LEVEL [thread]`) |
| `AccessLogParser.java` | Nginx/Apache combined access logs; level derived from HTTP status |
| `GenericLogParser.java` | Fallback: any line starting with a timestamp; level/service sniffed from text |
| `LogParserFactory.java` | Ordered parsers, per-line auto-detection, preferred-parser fast path, majority-vote detection |
| `LogEventAssembler.java` | Multi-line stack-trace assembly, unparsed-line and format statistics |
| `TimestampParser.java` | Many timestamp formats → `Instant` UTC |
| `KeyValueExtractor.java` | `key=value` extraction with aliases (traceId, spanId, service, duration...) |
| `ParseContext.java` | Default service (derived from file name) and zone |
| `LogFormat.java` | Supported formats |

### `processing.detection` – failure detection (Chain of Responsibility)
| File | Purpose |
|---|---|
| `FailureDetector.java` | Detector strategy interface with `order()` |
| `AbstractPatternDetector.java` | Template Method: candidate filter → pattern match → finding with evidence |
| `RetryExhaustionDetector.java` | "retry attempt n/n", max retries, Resilience4j `MaxRetriesExceededException` |
| `DatabaseFailureDetector.java` | SQL exceptions, HikariCP exhaustion, deadlocks, constraint violations |
| `TimeoutDetector.java` | Socket/read timeouts, "timed out", 504 |
| `CommunicationFailureDetector.java` | Connection refused, 502/503, circuit breaker open, DNS |
| `ExceptionDetector.java` | Catch-all for errors with stack traces/exceptions |
| `DetectionEngine.java` | Ordered chain; first match wins |
| `ServiceReferenceExtractor.java` | Finds the downstream service mentioned in a message (target service) |

### `processing.flow` – transaction tracking
| File | Purpose |
|---|---|
| `TraceAggregator.java` | `ConcurrentHashMap` of traceId → events, with memory caps |
| `TransactionFlowAnalyzer.java` | Timeline, span-based and heuristic call edges, missing/late responses |
| `ServiceCallGraph.java` | Thread-safe directed graph with edge stats, BFS downstream, callers |
| `TransactionFlow.java`, `FlowStep.java`, `CallEdge.java`, `MissingResponse.java` | Result records |

### `processing.ingestion` – streaming pipeline
| File | Purpose |
|---|---|
| `LogIngestionPipeline.java` | BufferedReader streaming, chunked `CompletableFuture` processing, backpressure, progress, metrics |
| `EventSink.java` / `JpaEventSink.java` | Persistence port and its batched JPA adapter |
| `CountingInputStream.java` | Byte counter for progress reporting |
| `IngestionResult.java` | Counters, findings, trace aggregator |

### `processing.masking`
| File | Purpose |
|---|---|
| `PiiMasker.java` | Masks emails, Luhn-valid cards, JWT/bearer tokens, secrets before storage and LLM |

### `rca` – root cause analysis
| File | Purpose |
|---|---|
| `RcaEngine.java` | Clustering → sink detection → scoring → root, confidence, causal chain, timeline |
| `IncidentClusterer.java` | Splits findings into incidents by time gap |
| `RecommendationEngine.java` / `RecommendationRule.java` | Rule-based remediation keyed by category and message triggers |
| `RcaResult.java`, `TimelineItem.java` | Result records |

### `ai` – summaries
| File | Purpose |
|---|---|
| `AiSummarizer.java` | Sealed interface (template or LLM) |
| `TemplateSummarizer.java` | Deterministic summary (always available) |
| `OpenAiCompatibleSummarizer.java` | `/v1/chat/completions` client (OpenAI, Ollama, vLLM); falls back to template |
| `PromptBuilder.java` | Builds a bounded prompt from masked, condensed findings only |

### `reporting`
| File | Purpose |
|---|---|
| `PdfReportGenerator.java` | OpenPDF RCA report |
| `JsonReportExporter.java` | Versioned JSON export envelope |
| `DashboardService.java` | Streams `groupingBy` aggregates: categories, severities, services, durations, confidence |

### `repository`
| File | Purpose |
|---|---|
| `AnalysisJobRepository.java` | Jobs, progress update query, counts by status |
| `LogEventRepository.java` | Events by job/trace |
| `IncidentRepository.java` | Incidents + `JpaSpecificationExecutor` |
| `IncidentSpecifications.java` | Dynamic filters (service via EXISTS on findings, severity, category, job, date range) |
| `FindingRepository.java`, `ServiceCallEdgeRepository.java` | Findings and edges |

### `service`
| File | Purpose |
|---|---|
| `JobService.java` | Validates and stores uploads, creates jobs, queries jobs and service graph |
| `JobSubmittedEvent.java` / `JobDispatcher.java` | Observer: dispatch to the job executor after the transaction commits |
| `AnalysisJobProcessor.java` | Orchestrates ingest → flows → RCA → incidents → completion; failure handling |
| `AnalysisCompletedEvent.java` / `AnalysisCompletionListener.java` | Completion notification (logging hook) |
| `IncidentService.java` | Search, detail, by-job queries |
| `TransactionFlowService.java` | Rebuilds a trace flow from persisted events |
| `ReportService.java` | PDF/JSON report orchestration |
| `LogSenseMetrics.java` | Custom Micrometer metrics |

### `dto` and `mapper`
| File | Purpose |
|---|---|
| `dto/*` | API records: `JobResponse`, `AnalyzeTextRequest` (Bean Validation), `PageResponse`, `IncidentSummaryDto`, `IncidentDetailDto`, `FindingDto`, `TimelineEntryDto`, `ServiceEdgeDto`, `DashboardDto`, `ServiceFailureCount`, `IncidentSearchCriteria`, `IncidentReportDto` |
| `mapper/JobMapper.java`, `mapper/IncidentMapper.java` | Entity ↔ DTO / RCA result → entity |

### `exception`
| File | Purpose |
|---|---|
| `GlobalExceptionHandler.java` | RFC 7807 `ProblemDetail` for all errors, validation details |
| `ResourceNotFoundException`, `InvalidLogFileException`, `InvalidRequestException`, `StorageException`, `ReportGenerationException` | Domain exceptions |

### `kafka`
| File | Purpose |
|---|---|
| `KafkaLogIngestionListener.java` | Optional (`kafka` profile) batch listener feeding the same pipeline |

## `src/main/resources`
| File | Purpose |
|---|---|
| `application.yml` | Default config (PostgreSQL, Flyway, JPA batching, multipart 500 MB, actuator, springdoc, logsense.*) |
| `application-kafka.yml` | Enables Kafka auto-config and listener settings |
| `db/migration/V1__init.sql` | Schema, sequence and indexes |

## `src/test`
| File | Purpose |
|---|---|
| `processing/parser/*Test` | Parsers, timestamps (parameterised), factory auto-detection, multi-line assembly |
| `processing/detection/FailureDetectorsTest` | Each detector + chain ordering |
| `processing/flow/TransactionFlowAnalyzerTest` | Span/heuristic edges, missing and late responses, aggregator caps, graph |
| `processing/ingestion/LogIngestionPipelineTest` | 50k-line streaming run with parallel chunks; PII masked before persistence |
| `processing/masking/PiiMaskerTest` | Masking rules |
| `rca/RcaEngineTest`, `rca/RecommendationEngineTest` | Cascade root cause, symptom-first ordering, retry exhaustion, clustering |
| `ai/SummarizerTest` | Template summary, prompt masking, LLM client success and fallback (MockRestServiceServer) |
| `reporting/ReportingTest` | Dashboard aggregation, PDF bytes, JSON envelope |
| `api/*ControllerTest` | `@WebMvcTest`: 202/Location, validation 400s, 404 ProblemDetail, PDF content type |
| `integration/LogSenseIntegrationTest` | `@SpringBootTest` + H2: all samples end-to-end, reports, dashboard, metrics, generated file |
| `integration/PostgresContainerTest` | Testcontainers PostgreSQL + Flyway (skipped without Docker) |
| `support/TestEvents.java` | Test data builders |
| `tools/LogGenerator.java` | Large realistic log generator (single-file runnable) |
| `resources/application-test.yml` | H2 `MODE=PostgreSQL`, Flyway off, `create-drop` |