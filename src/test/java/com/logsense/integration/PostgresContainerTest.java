package com.logsense.integration;

import com.logsense.domain.model.JobStatus;
import com.logsense.dto.JobResponse;
import com.logsense.repository.IncidentRepository;
import com.logsense.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the real Flyway migrations against PostgreSQL 16. Skipped automatically when Docker is not available
 * (e.g. on a developer laptop without a running daemon); CI runners with Docker execute it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logsense.storage.upload-dir=target/test-uploads-pg"})
class PostgresContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JobService jobService;
    @Autowired
    private IncidentRepository incidentRepository;

    @Test
    void flywaySchemaSupportsFullPipeline() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("samples", "cascade-db-pool-exhaustion.log"));
        JobResponse job = jobService.submit(new MockMultipartFile("file", "cascade.log", "text/plain", bytes));

        await().atMost(Duration.ofSeconds(60)).until(() -> jobService.getJob(job.id()).status().isTerminal());

        assertThat(jobService.getJob(job.id()).status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(incidentRepository.findByJobIdOrderByFirstFailureAtAsc(job.id()))
                .extracting("rootCauseService").containsExactly("inventory-service");
    }
}