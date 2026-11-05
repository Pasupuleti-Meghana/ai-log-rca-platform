package com.logsense.repository;

import com.logsense.domain.entity.LogEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LogEventRepository extends JpaRepository<LogEventEntity, Long> {

    List<LogEventEntity> findByJobIdAndTraceIdOrderByTimestampAscLineNumberAsc(UUID jobId, String traceId);

    long countByJobId(UUID jobId);
}
