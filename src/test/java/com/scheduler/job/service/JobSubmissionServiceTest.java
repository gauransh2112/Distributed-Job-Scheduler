package com.scheduler.job.service;

import com.scheduler.job.config.JobProperties;
import com.scheduler.job.dto.request.CreateJobRequest;
import com.scheduler.job.dto.response.CreateJobResponse;
import com.scheduler.job.dto.response.JobResponse;
import com.scheduler.job.dto.response.JobSummaryResponse;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.DuplicateJobException;
import com.scheduler.job.exception.JobNotFoundException;
import com.scheduler.job.mapper.JobMapper;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.impl.JobQueryServiceImpl;
import com.scheduler.job.service.impl.JobSubmissionServiceImpl;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobSubmissionServiceTest {

    @Mock
    private JobRepository jobRepository;

    private JobMapper jobMapper;
    private JobProperties jobProperties;
    private JobSubmissionService jobSubmissionService;
    private JobQueryService jobQueryService;

    @BeforeEach
    void setUp() {
        jobMapper = Mappers.getMapper(JobMapper.class);
        jobProperties = new JobProperties(5);
        jobSubmissionService = new JobSubmissionServiceImpl(jobRepository, jobMapper, jobProperties);
        jobQueryService = new JobQueryServiceImpl(jobRepository, jobMapper);
    }

    @Test
    @DisplayName("Should successfully create job when idempotency key is unique")
    void createJob_Success() {
        CreateJobRequest request = new CreateJobRequest(
                "SEND_EMAIL",
                "{\"to\":\"user@example.com\"}",
                Instant.now().plusSeconds(60),
                "IDEM-KEY-001"
        );

        when(jobRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(jobRepository.saveAndFlush(any(JobEntity.class))).thenAnswer(invocation -> {
            JobEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return entity;
        });

        CreateJobResponse response = jobSubmissionService.createJob(request);

        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals("SEND_EMAIL", response.jobType());
        assertEquals(JobStatus.PENDING, response.status());
        assertEquals("IDEM-KEY-001", response.idempotencyKey());

        verify(jobRepository, times(1)).findByIdempotencyKey("IDEM-KEY-001");
        verify(jobRepository, times(1)).saveAndFlush(any(JobEntity.class));
    }

    @Test
    @DisplayName("Should throw DuplicateJobException when idempotency key already exists in pre-check")
    void createJob_DuplicatePreCheck() {
        CreateJobRequest request = new CreateJobRequest(
                "SEND_EMAIL",
                "{\"to\":\"user@example.com\"}",
                Instant.now().plusSeconds(60),
                "IDEM-KEY-001"
        );

        JobEntity existingEntity = JobEntity.builder()
                .id(UUID.randomUUID())
                .jobType(request.jobType())
                .idempotencyKey(request.idempotencyKey())
                .status(JobStatus.PENDING)
                .build();

        when(jobRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.of(existingEntity));

        assertThrows(DuplicateJobException.class, () -> jobSubmissionService.createJob(request));
        verify(jobRepository, never()).saveAndFlush(any(JobEntity.class));
    }

    @Test
    @DisplayName("Should throw DuplicateJobException when ConstraintViolationException metadata matches jobs_idempotency_key_key")
    void createJob_IdempotencyConstraintViolation_ThrowsDuplicateJobException() {
        CreateJobRequest request = new CreateJobRequest(
                "SEND_EMAIL",
                "{\"to\":\"user@example.com\"}",
                Instant.now().plusSeconds(60),
                "IDEM-KEY-001"
        );

        ConstraintViolationException cve = new ConstraintViolationException(
                "Unique constraint violation", new SQLException(), "jobs_idempotency_key_key");
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(jobRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(jobRepository.saveAndFlush(any(JobEntity.class))).thenThrow(dive);

        assertThrows(DuplicateJobException.class, () -> jobSubmissionService.createJob(request));
    }

    @Test
    @DisplayName("Should rethrow DataIntegrityViolationException when ConstraintViolationException metadata does not match idempotency constraint name")
    void createJob_OtherConstraintViolation_RethrowsException() {
        CreateJobRequest request = new CreateJobRequest(
                "SEND_EMAIL",
                "{\"to\":\"user@example.com\"}",
                Instant.now().plusSeconds(60),
                "IDEM-KEY-001"
        );

        ConstraintViolationException cve = new ConstraintViolationException(
                "Foreign key violation", new SQLException(), "fk_jobs_other_table");
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(jobRepository.findByIdempotencyKey(request.idempotencyKey())).thenReturn(Optional.empty());
        when(jobRepository.saveAndFlush(any(JobEntity.class))).thenThrow(dive);

        assertThrows(DataIntegrityViolationException.class, () -> jobSubmissionService.createJob(request));
    }

    @Test
    @DisplayName("Should retrieve job details when job exists")
    void getJob_Success() {
        UUID jobId = UUID.randomUUID();
        JobEntity entity = JobEntity.builder()
                .id(jobId)
                .jobType("GENERATE_REPORT")
                .payload("{\"reportId\":1}")
                .status(JobStatus.PENDING)
                .scheduledAt(Instant.now().plusSeconds(300))
                .retryCount(0)
                .maxRetries(5)
                .idempotencyKey("IDEM-123")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        JobResponse response = jobQueryService.getJob(jobId);

        assertNotNull(response);
        assertEquals(jobId, response.id());
        assertEquals("GENERATE_REPORT", response.jobType());
        assertEquals(JobStatus.PENDING, response.status());
    }

    @Test
    @DisplayName("Should throw JobNotFoundException when job ID does not exist")
    void getJob_NotFound() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> jobQueryService.getJob(jobId));
    }

    @Test
    @DisplayName("Should list jobs filtered by status")
    void listJobs_WithStatusFilter() {
        Pageable pageable = PageRequest.of(0, 10);
        JobEntity entity = JobEntity.builder()
                .id(UUID.randomUUID())
                .jobType("CLEANUP")
                .status(JobStatus.PENDING)
                .scheduledAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        Page<JobEntity> entityPage = new PageImpl<>(List.of(entity), pageable, 1);
        when(jobRepository.findByStatus(JobStatus.PENDING, pageable)).thenReturn(entityPage);

        Page<JobSummaryResponse> result = jobQueryService.listJobs(JobStatus.PENDING, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(JobStatus.PENDING, result.getContent().get(0).status());
    }
}
