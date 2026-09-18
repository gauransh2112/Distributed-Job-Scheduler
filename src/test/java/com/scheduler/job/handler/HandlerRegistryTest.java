package com.scheduler.job.handler;

import com.scheduler.job.exception.HandlerNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for handler registration and resolution.
 */
class HandlerRegistryTest {

    @Test
    @DisplayName("Handlers register themselves by job type and resolve back to the same instance")
    void testRegistrationAndResolution() {
        JobHandler email = handler("SEND_EMAIL");
        JobHandler cleanup = handler("CLEANUP");
        HandlerRegistry registry = new HandlerRegistry(List.of(email, cleanup));

        assertSame(email, registry.resolve("SEND_EMAIL"));
        assertSame(cleanup, registry.resolve("CLEANUP"));
        assertEquals(2, registry.registrations().size());
    }

    @Test
    @DisplayName("Registered job types are reported for diagnostics")
    void testRegisteredJobTypes() {
        HandlerRegistry registry = new HandlerRegistry(
                List.of(handler("GENERATE_REPORT"), handler("SEND_EMAIL")));

        assertEquals(List.of("GENERATE_REPORT", "SEND_EMAIL"), List.copyOf(registry.registeredJobTypes()));
        assertTrue(registry.supports("SEND_EMAIL"));
        assertFalse(registry.supports("UNKNOWN"));
        assertFalse(registry.supports(null));
    }

    @Test
    @DisplayName("An unknown job type fails explicitly rather than being treated as a no-op success")
    void testUnknownJobTypeFailsExplicitly() {
        HandlerRegistry registry = new HandlerRegistry(List.of(handler("SEND_EMAIL")));

        HandlerNotFoundException thrown =
                assertThrows(HandlerNotFoundException.class, () -> registry.resolve("NOT_A_JOB_TYPE"));

        assertTrue(thrown.getMessage().contains("NOT_A_JOB_TYPE"));
        // The message lists what IS registered, so the failure is diagnosable from the log alone.
        assertTrue(thrown.getMessage().contains("SEND_EMAIL"));
    }

    @Test
    @DisplayName("A null job type is rejected the same way as an unknown one")
    void testNullJobTypeRejected() {
        HandlerRegistry registry = new HandlerRegistry(List.of(handler("SEND_EMAIL")));

        assertThrows(HandlerNotFoundException.class, () -> registry.resolve(null));
    }

    @Test
    @DisplayName("Two handlers claiming the same job type abort startup instead of silently picking one")
    void testDuplicateRegistrationRejected() {
        JobHandler first = handler("SEND_EMAIL");
        JobHandler second = handler("SEND_EMAIL");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new HandlerRegistry(List.of(first, second)));

        assertTrue(thrown.getMessage().contains("Duplicate handler registration"));
        assertTrue(thrown.getMessage().contains("SEND_EMAIL"));
    }

    @Test
    @DisplayName("A handler declaring a blank or null job type aborts startup")
    void testBlankJobTypeRejected() {
        assertThrows(IllegalStateException.class, () -> new HandlerRegistry(List.of(handler("  "))));
        assertThrows(IllegalStateException.class, () -> new HandlerRegistry(List.of(handler(null))));
    }

    @Test
    @DisplayName("An empty registry resolves nothing but still fails explicitly")
    void testEmptyRegistry() {
        HandlerRegistry registry = new HandlerRegistry(List.of());

        assertTrue(registry.registeredJobTypes().isEmpty());
        assertThrows(HandlerNotFoundException.class, () -> registry.resolve("SEND_EMAIL"));
    }

    @Test
    @DisplayName("The registration index cannot be mutated through the exposed view")
    void testRegistrationsAreDefensive() {
        HandlerRegistry registry = new HandlerRegistry(List.of(handler("SEND_EMAIL")));

        registry.registrations().clear();
        registry.registeredJobTypes().clear();

        assertTrue(registry.supports("SEND_EMAIL"), "Registry must not be mutable from outside");
    }

    private JobHandler handler(String jobType) {
        return new JobHandler() {
            @Override
            public String jobType() {
                return jobType;
            }

            @Override
            public void execute(String payload) {
                // no-op test double
            }
        };
    }
}
