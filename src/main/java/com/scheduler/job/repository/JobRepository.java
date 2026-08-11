package com.scheduler.job.repository;

import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for JobEntity persistence operations.
 * 
 * Phase 1 Scope: Core CRUD, Idempotency key lookup, and status-based pagination.
 * Excludes Phase 2 claiming queries (FOR UPDATE SKIP LOCKED).
 */
@Repository
public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    /**
     * Find job by idempotency key to enforce single-submission semantics.
     *
     * @param idempotencyKey unique submission key provided by client
     * @return Optional containing matching JobEntity if found
     */
    Optional<JobEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * Retrieve paginated list of jobs filtered by status.
     *
     * @param status job lifecycle status filter
     * @param pageable pagination parameters (page, size, sort)
     * @return Page of matching JobEntity records
     */
    Page<JobEntity> findByStatus(JobStatus status, Pageable pageable);
}
