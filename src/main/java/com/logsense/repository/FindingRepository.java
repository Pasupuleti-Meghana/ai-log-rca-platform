package com.logsense.repository;

import com.logsense.domain.entity.FindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FindingRepository extends JpaRepository<FindingEntity, UUID> {

    long countByJobId(UUID jobId);
}
