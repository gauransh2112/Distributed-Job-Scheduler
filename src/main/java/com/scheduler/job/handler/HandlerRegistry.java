package com.scheduler.job.handler;

import com.scheduler.job.exception.HandlerNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves the {@link JobHandler} for a job type.
 *
 * <p>Handlers register themselves by being Spring beans: every {@code JobHandler} on the classpath is
 * injected here and indexed by {@link JobHandler#jobType()}. Adding a job type means adding a handler,
 * with no registration list to keep in step.
 *
 * <p><strong>Conflicts fail at startup.</strong> Two handlers claiming the same job type, or a handler
 * with a blank type, aborts context refresh. Resolving such a conflict at runtime would mean silently
 * picking one of two implementations, and which one depends on bean ordering — a bug that would surface
 * as jobs intermittently doing the wrong thing.
 *
 * <p>The index is built once during construction and never mutated, so lookups are safe from the many
 * worker threads that share this bean.
 */
@Component
public class HandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(HandlerRegistry.class);

    private final Map<String, JobHandler> handlersByType;

    public HandlerRegistry(List<JobHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers must not be null");

        Map<String, JobHandler> index = new HashMap<>(handlers.size());
        for (JobHandler handler : handlers) {
            String jobType = handler.jobType();
            if (jobType == null || jobType.isBlank()) {
                throw new IllegalStateException(String.format(
                        "Handler %s declares a null or blank job type", handler.getClass().getName()));
            }

            JobHandler previous = index.put(jobType, handler);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "Duplicate handler registration for job type '%s': %s and %s",
                        jobType, previous.getClass().getName(), handler.getClass().getName()));
            }
        }

        this.handlersByType = Map.copyOf(index);
        log.info("Registered {} job handler(s): {}", handlersByType.size(), registeredJobTypes());
    }

    /**
     * Resolves the handler for a job type.
     *
     * @param jobType the job type to resolve
     * @return the registered handler, never null
     * @throws HandlerNotFoundException if no handler is registered for the type
     */
    public JobHandler resolve(String jobType) {
        JobHandler handler = jobType == null ? null : handlersByType.get(jobType);
        if (handler == null) {
            // Explicit failure: an unknown type must never be treated as a no-op success, or a job
            // nobody can run would be recorded as if it had run.
            log.error("No handler registered for job type '{}'. Registered types: {}",
                    jobType, registeredJobTypes());
            throw new HandlerNotFoundException(String.format(
                    "No handler registered for job type '%s'. Registered types: %s",
                    jobType, registeredJobTypes()));
        }
        return handler;
    }

    /**
     * @param jobType the job type to check
     * @return true if a handler is registered for the type
     */
    public boolean supports(String jobType) {
        return jobType != null && handlersByType.containsKey(jobType);
    }

    /**
     * @return the registered job types, sorted for stable logging and diagnostics
     */
    public Set<String> registeredJobTypes() {
        return new TreeSet<>(handlersByType.keySet());
    }

    /**
     * @return an unmodifiable view of the registrations, keyed by job type
     */
    public Map<String, JobHandler> registrations() {
        return new LinkedHashMap<>(handlersByType);
    }
}
