package com.scheduler.job.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the handler contract across every implementation, including ones added later.
 *
 * <p>Handlers are shared singletons executed from many worker threads, and they must stay ignorant of
 * the database, the queue, the scheduler and the retry engine. Both rules are easy to break by adding
 * one innocuous-looking field, so they are checked by scanning the handler package rather than by
 * reviewing each new class by hand.
 */
class JobHandlerContractTest {

    private static final String HANDLER_PACKAGE = "com.scheduler.job.handler";

    /**
     * Packages a handler must not reach into. A handler that needs any of these has been given a
     * responsibility that belongs to the worker.
     */
    private static final Set<String> FORBIDDEN_PACKAGE_PREFIXES = Set.of(
            "com.scheduler.job.repository",
            "com.scheduler.job.queue",
            "com.scheduler.job.scheduler",
            "com.scheduler.job.service",
            "software.amazon.awssdk",
            "org.springframework.data",
            "jakarta.persistence",
            "javax.sql");

    @Test
    @DisplayName("Every handler is discoverable as a Spring component")
    void testHandlersAreDiscoverable() {
        List<Class<?>> handlers = findHandlerImplementations();

        // Asserted concretely so the scan cannot silently return nothing and make the contract
        // checks below pass vacuously.
        assertTrue(handlers.containsAll(List.of(
                        SendEmailJobHandler.class, GenerateReportJobHandler.class, CleanupJobHandler.class)),
                "Handler scan did not find the known handlers; found: " + handlers);
        for (Class<?> handler : handlers) {
            assertNotNull(handler.getAnnotation(Component.class),
                    handler.getSimpleName() + " must be a @Component so the registry can find it");
        }
    }

    @Test
    @DisplayName("The three specified handlers exist and cover the expected job types")
    void testExpectedHandlersRegistered() {
        List<JobHandler> handlers = List.of(
                new SendEmailJobHandler(new com.fasterxml.jackson.databind.ObjectMapper()),
                new GenerateReportJobHandler(new com.fasterxml.jackson.databind.ObjectMapper()),
                new CleanupJobHandler(new com.fasterxml.jackson.databind.ObjectMapper()));

        HandlerRegistry registry = new HandlerRegistry(handlers);

        assertTrue(registry.supports("SEND_EMAIL"));
        assertTrue(registry.supports("GENERATE_REPORT"));
        assertTrue(registry.supports("CLEANUP"));
    }

    @Test
    @DisplayName("Handlers hold no mutable state, since one instance serves every worker thread")
    void testHandlersAreStateless() {
        for (Class<?> handler : findHandlerImplementations()) {
            for (Field field : handler.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertTrue(Modifier.isFinal(field.getModifiers()),
                        String.format("%s.%s must be final: a handler instance is shared across threads",
                                handler.getSimpleName(), field.getName()));
            }
        }
    }

    @Test
    @DisplayName("Handlers know nothing about the database, queue, scheduler or retry engine")
    void testHandlersHaveNoInfrastructureDependencies() {
        for (Class<?> handler : findHandlerImplementations()) {
            for (Field field : handler.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                Package fieldPackage = field.getType().getPackage();
                String packageName = fieldPackage == null ? "" : fieldPackage.getName();

                for (String forbidden : FORBIDDEN_PACKAGE_PREFIXES) {
                    assertFalse(packageName.startsWith(forbidden),
                            String.format("%s.%s pulls in %s; handlers execute business work only",
                                    handler.getSimpleName(), field.getName(), packageName));
                }
            }
        }
    }

    /**
     * Finds concrete {@link JobHandler} implementations regardless of annotation, so a handler that
     * forgot {@code @Component} is still caught by the checks above.
     */
    private List<Class<?>> findHandlerImplementations() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(JobHandler.class));

        List<Class<?>> handlers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(HANDLER_PACKAGE)) {
            try {
                handlers.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Could not load scanned handler class", e);
            }
        }
        return handlers;
    }
}
