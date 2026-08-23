package com.scheduler.job.repository;

import com.scheduler.job.entity.JobEntity;
import com.scheduler.job.entity.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for JobEntity persistence operations.
 * Includes Phase 2 PostgreSQL atomic claiming queries (FOR UPDATE SKIP LOCKED).
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

    /**
     * Atomically select and row-lock due PENDING jobs for claiming using PostgreSQL FOR UPDATE SKIP LOCKED.
     * Concurrently executing schedulers will skip rows currently locked by other transactions, preventing wait-blocking.
     *
     * @param now current timestamp to filter due jobs (scheduled_at <= now)
     * @param limit maximum batch size to claim
     * @return List of locked JobEntity records ready for claiming
     */
    @Query(value = """
            SELECT * FROM jobs
            WHERE status = 'PENDING'
              AND scheduled_at <= :now
            ORDER BY scheduled_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<JobEntity> findEligibleJobsForClaim(
            @Param("now") Instant now,
            @Param("limit") int limit
    );
}

