LogSense – AI Log Analysis & Root Cause Analysis Platform
LogSense is a Spring Boot 3 / Java 21 backend that ingests logs from distributed microservices and:

Reconstructs distributed transactions
Detects failures and incidents
Identifies which service failed first and why
Generates Root Cause Analysis (RCA) reports through APIs, PDF reports, JSON exports, and dashboards
Problem Statement
In a microservice architecture, a single fault (for example, an exhausted database connection pool in inventory-service) may surface as downstream timeouts in order-service and 504 Gateway Timeout errors at the api-gateway.

During production incidents, engineers often need to manually inspect logs across different services, correlate trace IDs, reconstruct call flows, and determine the actual source of failure.

Most monitoring systems alert on the symptom rather than the cause.

LogSense automates log correlation, distributed transaction reconstruction, incident analysis, and root cause identification while providing an explainable confidence score.

Sample RCA Output
{
  "rootCauseService": "inventory-service",
  "rootCauseCategory": "DATABASE",
  "confidence": 0.95,
  "causalChain": [
    "api-gateway",
    "order-service",
    "inventory-service"
  ],
  "recommendation": "Check for connection leaks and review HikariCP pool sizing."
}
Features
Area	Description
Ingestion	Upload logs up to 500 MB through asynchronous jobs with progress tracking
Parsing	Supports JSON, Spring Boot, Log4j, Nginx, Apache, and mixed-format logs
Extraction	Extracts timestamps, trace IDs, correlation IDs, spans, exceptions, statuses, durations
Transaction Tracking	Reconstructs distributed call chains and service graphs
Failure Detection	Detects database, timeout, retry exhaustion, communication, and generic failures
RCA Engine	Determines probable root cause, causal chain, and confidence score
AI Summary	Generates deterministic RCA summaries with optional LLM enhancement
Reporting	Dashboard, JSON export, PDF generation
Operations	OpenAPI, Prometheus metrics, Flyway migrations, Actuator endpoints, Docker deployment
Architecture
flowchart LR
    U[Client / Swagger / Postman] -->|POST /api/v1/jobs| API[REST API]
    K[(Kafka<br/>optional profile)] --> SVC
    API --> SVC[JobService] -->|event after commit| EX[Bounded job executor]
    EX --> PIPE[Streaming pipeline<br/>BufferedReader → chunks]
    PIPE --> PARSE[Parser strategies<br/>+ multi-line assembler]
    PARSE --> MASK[PII masking] --> DET[Detector chain]
    PIPE --> TRACE[TraceAggregator<br/>ConcurrentHashMap]
    TRACE --> FLOW[Transaction flow<br/>+ call graph]
    DET --> RCA[RCA engine]
    FLOW --> RCA
    RCA --> REC[Recommendations] & SUM[Template / LLM summary]
    RCA --> DB[(PostgreSQL)]
    PIPE -->|JDBC batch| DB
    DB --> REP[Dashboard · PDF · JSON]
Details are available in:

docs/ARCHITECTURE.md
docs/ER-DIAGRAM.md
docs/API.md
docs/DEPLOYMENT.md
docs/FILE-GUIDE.md
Architecture documentation includes:

Sequence diagrams
RCA analysis logic
Design trade-offs
Scaling considerations
Tech Stack
Area	Technologies
Core	Java 21, Spring Boot 3, Spring Web, Bean Validation
Data	Spring Data JPA, Hibernate 6, PostgreSQL 16, Flyway
API & Monitoring	OpenAPI, Actuator, Micrometer Prometheus
Reports & AI	OpenPDF, Jackson, OpenAI-Compatible APIs
Optional	Spring Kafka
Testing	JUnit 5, Mockito, MockMvc, Awaitility, H2, Testcontainers
Deployment	Docker, Docker Compose, GitHub Actions
Quick Start
Option A: Docker Compose
docker compose up --build -d
Option B: Local PostgreSQL
mvn clean verify
java -jar target/ai-log-rca-platform-1.0.0.jar
Available endpoints:

Swagger UI: http://localhost:8080/swagger-ui.html
OpenAPI: http://localhost:8080/v3/api-docs
Metrics: http://localhost:8080/actuator/prometheus
Sample Walkthrough
Sample datasets are available under samples/.

File	Scenario	Expected Root Cause
cascade-db-pool-exhaustion.log	Hikari connection pool exhaustion	inventory-service
retry-exhaustion.log	Retry exhaustion after dependency failures	payment-service
json-format.log	Database deadlock	order-service
mixed-format.log	Mixed log formats	Multiple incidents
Upload
curl -F "file=@samples/cascade-db-pool-exhaustion.log" \
http://localhost:8080/api/v1/jobs
Check Job Status
curl http://localhost:8080/api/v1/jobs/{jobId}
View RCA
curl http://localhost:8080/api/v1/jobs/{jobId}/incidents
Transaction Flow
curl http://localhost:8080/api/v1/jobs/{jobId}/service-graph
Generate Reports
curl -o rca.pdf \
http://localhost:8080/api/v1/incidents/{incidentId}/report/pdf
Performance Demo
Generate a large synthetic log file:

Windows
java src\test\java\com\logsense\tools\LogGenerator.java target\large.log 1000000
curl.exe -F "file=@target\large.log" http://localhost:8080/api/v1/jobs
Linux / macOS
java src/test/java/com/logsense/tools/LogGenerator.java target/large.log 1000000

curl -F "file=@target/large.log" \
http://localhost:8080/api/v1/jobs
Tests
mvn clean verify
mvn test -Dtest=RcaEngineTest
mvn test -Dtest=LogSenseIntegrationTest
The project contains 100+ automated tests covering:

Log parsing
Stack-trace assembly
Failure detection
Root cause analysis
Recommendation generation
PII masking
PDF reporting
REST APIs
Dashboard functionality
End-to-end workflows
PostgreSQL integration tests using Testcontainers
Future Enhancements
Kafka-first ingestion with Kafka Streams or Flink
OpenTelemetry OTLP ingestion
ClickHouse or OpenSearch support
Durable job queues and recovery
RCA score learning from historical incidents
Anomaly detection on error rates
OAuth2 authentication and multi-tenancy
React dashboard with interactive graphs
Slack and PagerDuty notifications
Limitations
In-flight jobs are lost if the JVM terminates during processing
Trace aggregation maintains configurable in-memory state
Message-based service-call inference relies on heuristics
Kafka integration is currently not covered by automated tests
License
MIT License
