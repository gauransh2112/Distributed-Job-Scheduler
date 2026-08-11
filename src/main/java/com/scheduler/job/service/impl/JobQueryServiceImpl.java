package com.scheduler.job.service.impl;

import com.scheduler.job.dto.response.JobResponse;
import com.scheduler.job.dto.response.JobSummaryResponse;
import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import com.scheduler.job.exception.JobNotFoundException;
import com.scheduler.job.mapper.JobMapper;
import com.scheduler.job.repository.JobRepository;
import com.scheduler.job.service.JobQueryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementation of JobQueryService providing read-only queries for job records.
 */
@Service
@RequiredArgsConstructor
public class JobQueryServiceImpl implements JobQueryService {

    private static final Logger log = LoggerFactory.getLogger(JobQueryServiceImpl.class);

    private final JobRepository jobRepository;
    private final JobMapper jobMapper;

    @Override
    @Transactional(readOnly = true)
    public JobResponse getJob(UUID id) {
        log.debug("Fetching job details for ID: {}", id);
        JobEntity entity = jobRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Job not found for ID: {}", id);
                    return new JobNotFoundException(id);
                });
        return jobMapper.toJobResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JobSummaryResponse> listJobs(JobStatus status, Pageable pageable) {
        log.debug("Listing jobs with status filter: {} and pageable: {}", status, pageable);
        Page<JobEntity> page = (status != null)
                ? jobRepository.findByStatus(status, pageable)
                : jobRepository.findAll(pageable);
        return page.map(jobMapper::toJobSummaryResponse);
    }
}
