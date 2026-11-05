package com.logsense.service;

import com.logsense.config.LogSenseProperties;
import com.logsense.domain.entity.AnalysisJob;
import com.logsense.dto.AnalyzeTextRequest;
import com.logsense.dto.JobResponse;
import com.logsense.dto.PageResponse;
import com.logsense.dto.ServiceEdgeDto;
import com.logsense.exception.InvalidLogFileException;
import com.logsense.exception.ResourceNotFoundException;
import com.logsense.exception.StorageException;
import com.logsense.mapper.JobMapper;
import com.logsense.repository.AnalysisJobRepository;
import com.logsense.repository.ServiceCallEdgeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class JobService {

    private final AnalysisJobRepository jobRepository;
    private final ServiceCallEdgeRepository edgeRepository;
    private final JobMapper jobMapper;
    private final ApplicationEventPublisher events;
    private final LogSenseProperties.Storage storage;
    private Path uploadDir;

    public JobService(AnalysisJobRepository jobRepository, ServiceCallEdgeRepository edgeRepository,
                      JobMapper jobMapper, ApplicationEventPublisher events, LogSenseProperties properties) {
        this.jobRepository = jobRepository;
        this.edgeRepository = edgeRepository;
        this.jobMapper = jobMapper;
        this.events = events;
        this.storage = properties.storage();
    }

    @PostConstruct
    void init() {
        uploadDir = Path.of(storage.uploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new StorageException("Cannot create upload directory " + uploadDir, e);
        }
    }

    @Transactional
    public JobResponse submit(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidLogFileException("Uploaded file is empty");
        }
        String name = sanitize(file.getOriginalFilename());
        validateExtension(name);
        Path target = uploadDir.resolve(UUID.randomUUID() + "_" + name);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("Could not store uploaded file", e);
        }
        return createJob(name, file.getSize(), target);
    }

    @Transactional
    public JobResponse submitText(AnalyzeTextRequest request) {
        String name = sanitize(request.sourceName());
        Path target = uploadDir.resolve(UUID.randomUUID() + "_" + name);
        try {
            Files.writeString(target, request.content(), StandardCharsets.UTF_8);
            return createJob(name, Files.size(target), target);
        } catch (IOException e) {
            throw new StorageException("Could not store submitted log text", e);
        }
    }

    /** Used by streaming ingestion (Kafka) which already wrote a batch file into the upload directory. */
    @Transactional
    public JobResponse submitStoredFile(Path storedFile, String displayName) {
        try {
            return createJob(sanitize(displayName), Files.size(storedFile), storedFile);
        } catch (IOException e) {
            throw new StorageException("Could not read stored file", e);
        }
    }

    public Path uploadDir() {
        return uploadDir;
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(UUID id) {
        return jobRepository.findById(id).map(jobMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Analysis job", id));
    }

    @Transactional(readOnly = true)
    public PageResponse<JobResponse> listJobs(Pageable pageable) {
        return PageResponse.of(jobRepository.findAll(pageable).map(jobMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public List<ServiceEdgeDto> serviceGraph(UUID jobId) {
        requireJob(jobId);
        return edgeRepository.findByJobIdOrderByCallerAscCalleeAsc(jobId).stream()
                .map(e -> new ServiceEdgeDto(e.getCaller(), e.getCallee(), e.getCallCount(), e.getErrorCount(),
                        e.getAvgLatencyMs(), e.getMaxLatencyMs(),
                        e.getCallCount() == 0 ? 0.0 : Math.round(e.getErrorCount() * 1000.0 / e.getCallCount()) / 1000.0))
                .toList();
    }

    public void requireJob(UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new ResourceNotFoundException("Analysis job", jobId);
        }
    }

    private JobResponse createJob(String name, long size, Path stored) {
        AnalysisJob job = jobRepository.save(new AnalysisJob(name, size, stored.toString()));
        events.publishEvent(new JobSubmittedEvent(job.getId()));
        return jobMapper.toResponse(job);
    }

    private void validateExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!storage.allowedExtensions().contains(ext)) {
            throw new InvalidLogFileException("Unsupported file type '." + ext + "'. Allowed: "
                    + storage.allowedExtensions());
        }
    }

    static String sanitize(String original) {
        String name = original == null || original.isBlank() ? "upload.log" : original;
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank() || name.chars().allMatch(c -> c == '.')) {
            name = "upload.log";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
