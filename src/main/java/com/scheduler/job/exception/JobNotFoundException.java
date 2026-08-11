package com.scheduler.job.exception;

import java.util.UUID;

/**
 * Thrown when a requested job ID cannot be found in the database.
 */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(UUID id) {
        super(String.format("Job with ID '%s' was not found", id));
    }

    public JobNotFoundException(String message) {
        super(message);
    }
}
