package com.logsense.mapper;

import com.logsense.domain.entity.AnalysisJob;
import com.logsense.dto.JobResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class JobMapper {

    public JobResponse toResponse(AnalysisJob job) {
        Long took = null;
        if (job.getStartedAt() != null) {
            Instant end = job.getCompletedAt() != null ? job.getCompletedAt() : Instant.now();
            took = Duration.between(job.getStartedAt(), end).toMillis();
        }
        return new JobResponse(job.getId(), job.getFileName(), job.getFileSize(), job.getStatus(), job.getProgress(),
                job.getDetectedFormat(), job.getTotalLines(), job.getParsedEvents(), job.getUnparsedLines(),
                job.getFindingsCount(), job.getIncidentsCount(), job.getTraceCount(), job.getErrorMessage(),
                job.getCreatedAt(), job.getStartedAt(), job.getCompletedAt(), took);
    }
}
