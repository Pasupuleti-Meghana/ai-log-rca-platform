package com.logsense.repository;

import com.logsense.domain.entity.IncidentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID>, JpaSpecificationExecutor<IncidentEntity> {

    List<IncidentEntity> findByJobIdOrderByFirstFailureAtAsc(UUID jobId);
}
