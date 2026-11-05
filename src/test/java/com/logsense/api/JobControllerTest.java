package com.logsense.api;

import com.logsense.domain.model.JobStatus;
import com.logsense.dto.AnalyzeTextRequest;
import com.logsense.dto.JobResponse;
import com.logsense.exception.InvalidLogFileException;
import com.logsense.exception.ResourceNotFoundException;
import com.logsense.service.IncidentService;
import com.logsense.service.JobService;
import com.logsense.service.TransactionFlowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
class JobControllerTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mvc;
    @MockBean
    private JobService jobService;
    @MockBean
    private IncidentService incidentService;
    @MockBean
    private TransactionFlowService flowService;

    private static JobResponse queued() {
        return new JobResponse(JOB_ID, "order-service.log", 120, JobStatus.QUEUED, 0, null, 0, 0, 0, 0, 0, 0, null,
                Instant.parse("2024-10-15T10:00:00Z"), null, null, null);
    }

    @Test
    void uploadReturns202WithLocationHeader() throws Exception {
        when(jobService.submit(any())).thenReturn(queued());
        MockMultipartFile file = new MockMultipartFile("file", "order-service.log", "text/plain",
                "2024-10-15 10:00:00,000 [main] INFO com.a.App - hi".getBytes());

        mvc.perform(multipart("/api/v1/jobs").file(file))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", endsWith("/api/v1/jobs/" + JOB_ID)))
                .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void uploadWithoutFilePartIsBadRequestProblem() throws Exception {
        mvc.perform(multipart("/api/v1/jobs"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(jobService, never()).submit(any());
    }

    @Test
    void emptyFileIsRejectedWithProblemDetail() throws Exception {
        when(jobService.submit(any())).thenThrow(new InvalidLogFileException("Uploaded file is empty"));
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", "x.log", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://logsense.dev/problems/invalid-request"))
                .andExpect(jsonPath("$.detail").value("Uploaded file is empty"));
    }

    @Test
    void textAnalysisValidatesBody() throws Exception {
        mvc.perform(post("/api/v1/jobs/text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"bad name!\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.sourceName").exists())
                .andExpect(jsonPath("$.errors.content").exists());
        verify(jobService, never()).submitText(any(AnalyzeTextRequest.class));
    }

    @Test
    void textAnalysisAcceptsValidBody() throws Exception {
        when(jobService.submitText(any())).thenReturn(queued());
        mvc.perform(post("/api/v1/jobs/text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"order-service.log\",\"content\":\"line\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileName").value("order-service.log"));
    }

    @Test
    void unknownJobReturns404ProblemDetail() throws Exception {
        when(jobService.getJob(JOB_ID)).thenThrow(new ResourceNotFoundException("AnalysisJob", JOB_ID));
        mvc.perform(get("/api/v1/jobs/{id}", JOB_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value(containsString(JOB_ID.toString())))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void malformedJobIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/jobs/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void listRejectsOversizedPage() throws Exception {
        mvc.perform(get("/api/v1/jobs").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("size")));
    }
}