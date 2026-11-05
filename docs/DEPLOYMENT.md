# LogSense – Deployment Guide

## Prerequisites
- Java 21 and Maven 3.9+ (to build).
- PostgreSQL 14+ (runtime).
- Docker (optional): Docker Compose, plus the Testcontainers test, which is skipped automatically when Docker is not available.

## 1. Local (no Docker for the app)

```powershell
# 1. PostgreSQL: create database and user
psql -U postgres -c "CREATE USER logsense WITH PASSWORD 'logsense';"
psql -U postgres -c "CREATE DATABASE logsense OWNER logsense;"

# 2. Build and run (Flyway creates the schema on startup)
mvn clean verify
java -jar target\ai-log-rca-platform-1.0.0.jar
#   or: mvn spring-boot:run

# 3. Open Swagger UI
start http://localhost:8080/swagger-ui.html
```

To point at another database:
`$env:DB_URL="jdbc:postgresql://db-host:5432/logsense?reWriteBatchedInserts=true"`.

## 2. Docker Compose

```bash
docker compose up --build -d      # postgres:16 + app (multi-stage Dockerfile)
docker compose logs -f app
curl -F "file=@samples/cascade-db-pool-exhaustion.log" http://localhost:8080/api/v1/jobs
```

**Optional local LLM (Ollama):**
1. Uncomment the `ollama` service and its volume in `docker-compose.yml`.
2. Set `LOGSENSE_AI_ENABLED=true` and `LOGSENSE_AI_BASE_URL=http://ollama:11434`.
3. Run `docker compose exec ollama ollama pull llama3.1`.

Ollama exposes the OpenAI-compatible `/v1/chat/completions` endpoint, so no code changes are needed.

## 3. Kubernetes notes

**Deployment**
- Run 2+ replicas behind a Service.
- Probes:
  - `livenessProbe: /actuator/health/liveness`
  - `readinessProbe: /actuator/health/readiness`
- Resources:
  - Requests: `cpu: 1`, `memory: 1Gi`.
  - Limits: `memory: 2Gi`. `JAVA_OPTS=-XX:MaxRAMPercentage=75` adapts the heap to the limit.
  - CPU drives chunk parallelism (`logsense.processing.chunk-threads=0` means use all available CPUs).
- Graceful shutdown: `server.shutdown=graceful` and the executors drain for `await-termination-seconds`. Set
  `terminationGracePeriodSeconds` at least 10 s higher.

**Storage**
- Uploads are written to `LOGSENSE_UPLOAD_DIR`, which is processed and then deleted.
- Use an `emptyDir` sized for the largest upload. A job is processed by the pod that received it, so no shared volume is needed.
- For multi-pod durability, move uploads to object storage (S3/GCS) and drive jobs from Kafka.

**Configuration and observability**
- Secrets: `DB_PASSWORD` and `LOGSENSE_AI_API_KEY` come from a Kubernetes `Secret`. Everything else comes from a `ConfigMap`.
- Ingress: raise the body-size limit for uploads, e.g. `nginx.ingress.kubernetes.io/proxy-body-size: 510m`.
- Prometheus: scrape `/actuator/prometheus` via a ServiceMonitor. Suggested alerts:
  - `logsense_jobs_finished_total{status="FAILED"}` increasing
  - `logsense_job_processing_time_seconds` p95
- Database: use a managed PostgreSQL. Flyway migrates on startup; with many replicas, run migrations as an init Job or rely on Flyway's lock.

## 4. Environment variables

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/logsense?reWriteBatchedInserts=true` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `logsense` / `logsense` | DB credentials |
| `DB_POOL_SIZE` | `20` | Hikari max pool size |
| `SERVER_PORT` | `8080` | HTTP port |
| `LOGSENSE_UPLOAD_DIR` | `${java.io.tmpdir}/logsense-uploads` | where uploads are staged |
| `LOGSENSE_DELETE_UPLOADS` | `true` | delete the staged file after processing |
| `LOGSENSE_AI_ENABLED` | `false` | enable LLM summaries |
| `LOGSENSE_AI_BASE_URL` | `http://localhost:11434` | OpenAI-compatible base URL (OpenAI: `https://api.openai.com`) |
| `LOGSENSE_AI_API_KEY` | empty | Bearer token for the LLM endpoint |
| `LOGSENSE_AI_MODEL` | `llama3.1` | model name (e.g. `gpt-4o-mini`) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | only with profile `kafka` |
| `LOGSENSE_KAFKA_TOPIC` | `logsense.raw-logs` | only with profile `kafka` |
| `SPRING_PROFILES_ACTIVE` | – | `kafka` enables streaming ingestion |

**Tuning (`application.yml`, `logsense.processing.*`):**

| Property | Default |
|---|---|
| `batch-size` | 1000 |
| `chunk-threads` | 0 = number of CPUs |
| `chunk-queue-capacity` | 0 = 2 × threads |
| `max-traces` | 200000 |
| `max-events-per-trace` | 500 |
| `max-findings` | 10000 |
| `incident-gap` | 5m |
| `missing-response-timeout` | 30s |

`logsense.jobs.*` sizes the job executor.

## 5. Kafka profile

```bash
java -jar target/ai-log-rca-platform-1.0.0.jar --spring.profiles.active=kafka
```
Kafka auto-configuration is excluded by default, so the app and its tests never need a broker. The `kafka` profile
re-enables it and registers `KafkaLogIngestionListener`, a batch listener that turns each poll batch into a job.
Producers should use `traceId` as the record key. This path compiles but is **not covered by automated tests**.

## 6. CI
`.github/workflows/ci.yml` runs `mvn -B clean verify` on JDK 21, uploads the surefire reports, and builds the Docker
image. GitHub-hosted Ubuntu runners have Docker, so the Testcontainers PostgreSQL test (real Flyway migrations) runs there.