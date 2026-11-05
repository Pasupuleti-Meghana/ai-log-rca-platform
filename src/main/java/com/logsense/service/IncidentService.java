package com.logsense.service;

import com.logsense.dto.IncidentDetailDto;
import com.logsense.dto.IncidentSearchCriteria;
import com.logsense.dto.IncidentSummaryDto;
import com.logsense.dto.PageResponse;
import com.logsense.exception.ResourceNotFoundException;
import com.logsense.mapper.IncidentMapper;
import com.logsense.repository.IncidentRepository;
import com.logsense.repository.IncidentSpecifications;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IncidentService {

    private final IncidentRepository repository;
    private final IncidentMapper mapper;
    private final JobService jobService;

    public IncidentService(IncidentRepository repository, IncidentMapper mapper, JobService jobService) {
        this.repository = repository;
        this.mapper = mapper;
        this.jobService = jobService;
    }

    public PageResponse<IncidentSummaryDto> search(IncidentSearchCriteria criteria, Pageable pageable) {
        return PageResponse.of(repository.findAll(IncidentSpecifications.from(criteria), pageable).map(mapper::toSummary));
    }

    public IncidentDetailDto get(UUID id) {
        return repository.findById(id).map(mapper::toDetail)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", id));
    }

    public List<IncidentSummaryDto> byJob(UUID jobId) {
        jobService.requireJob(jobId);
        return repository.findByJobIdOrderByFirstFailureAtAsc(jobId).stream().map(mapper::toSummary).toList();
    }
}
