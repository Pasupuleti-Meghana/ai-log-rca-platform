package com.logsense.repository;

import com.logsense.domain.entity.ServiceCallEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceCallEdgeRepository extends JpaRepository<ServiceCallEdgeEntity, UUID> {

    List<ServiceCallEdgeEntity> findByJobIdOrderByCallerAscCalleeAsc(UUID jobId);
}
