package com.logsense.repository;

import com.logsense.domain.entity.AnalysisJob;
import com.logsense.domain.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    @Modifying
    @Transactional
    @Query("update AnalysisJob j set j.progress = :progress where j.id = :id")
    int updateProgress(@Param("id") UUID id, @Param("progress") int progress);

    long countByStatus(JobStatus status);
}
