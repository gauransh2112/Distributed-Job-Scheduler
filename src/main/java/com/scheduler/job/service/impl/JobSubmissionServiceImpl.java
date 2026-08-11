package com.scheduler.job.service.impl;

import com.scheduler.job.config.JobProperties;
import com.scheduler.job.dto.request.CreateJobRequest;
import com.scheduler.job.dto.response.CreateJobResponse;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.DuplicateJobException;
import com.scheduler.job.mapper.JobMapper;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.JobSubmissionService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of JobSubmissionService handling job submission and idempotency enforcement.
 */
@Service
@RequiredArgsConstructor
public class JobSubmissionServiceImpl implements JobSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(JobSubmissionServiceImpl.class);

    private static final String IDEMPOTENCY_CONSTRAINT_NAME = "jobs_idempotency_key_key";

    private final JobRepository jobRepository;
    private final JobMapper jobMapper;
    private final JobProperties jobProperties;

    @Override
    @Transactional
    public CreateJobResponse createJob(CreateJobRequest request) {
        log.info("Processing job submission request with idempotencyKey: {}", request.idempotencyKey());

        jobRepository.findByIdempotencyKey(request.idempotencyKey())
                .ifPresent(existingJob -> {
                    log.warn("Duplicate job submission rejected for idempotencyKey: {}", request.idempotencyKey());
                    throw new DuplicateJobException(request.idempotencyKey());
                });

        JobEntity entity = jobMapper.toEntity(request);
        entity.setStatus(JobStatus.PENDING);
        entity.setRetryCount(0);
        entity.setMaxRetries(jobProperties.maxRetries());

        try {
            JobEntity savedEntity = jobRepository.saveAndFlush(entity);
            log.info("Job successfully created with ID: {} and status: {}", savedEntity.getId(), savedEntity.getStatus());
            return jobMapper.toCreateJobResponse(savedEntity);
        } catch (DataIntegrityViolationException ex) {
            if (isIdempotencyConstraintViolation(ex)) {
                log.warn("Database unique constraint violation for idempotencyKey: {}", request.idempotencyKey());
                throw new DuplicateJobException(request.idempotencyKey());
            }
            throw ex;
        }
    }

    /**
     * Inspects structured Hibernate ConstraintViolationException metadata to verify if the violation
     * specifically targets the PostgreSQL 'jobs_idempotency_key_key' unique constraint.
     */
    private boolean isIdempotencyConstraintViolation(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException cve) {
            String constraintName = cve.getConstraintName();
            return constraintName != null && IDEMPOTENCY_CONSTRAINT_NAME.equalsIgnoreCase(constraintName);
        }
        return false;
    }
}
