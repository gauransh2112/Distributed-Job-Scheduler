package com.scheduler.job.queue;

import com.scheduler.job.config.AwsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches SQS queue URLs by configured queue name.
 *
 * <p>Resolution is lazy so that application startup does not require LocalStack/AWS to be reachable,
 * and cached because a queue URL is stable for the lifetime of the queue.
 *
 * <p>Auto-creation is configuration gated ({@code aws.sqs.auto-create-queues}) and intended for local
 * development and integration tests. In a deployed environment the queue is owned by infrastructure,
 * and a missing queue must surface as an error rather than be silently created by the application.
 */
@Component
@ConditionalOnProperty(prefix = "aws.sqs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqsQueueUrlProvider {

    private static final Logger log = LoggerFactory.getLogger(SqsQueueUrlProvider.class);

    private final SqsClient sqsClient;
    private final AwsProperties awsProperties;
    private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();

    public SqsQueueUrlProvider(SqsClient sqsClient, AwsProperties awsProperties) {
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient must not be null");
        this.awsProperties = Objects.requireNonNull(awsProperties, "awsProperties must not be null");
    }

    /**
     * @return URL of the main job execution queue
     */
    public String getQueueUrl() {
        return resolve(awsProperties.sqs().queueName());
    }

    /**
     * @return URL of the dead letter queue
     */
    public String getDlqUrl() {
        return resolve(awsProperties.sqs().dlqName());
    }

    /**
     * Resolves a queue URL, caching the result.
     *
     * <p>Only successful resolutions are cached: {@link ConcurrentHashMap#computeIfAbsent} does not
     * store a mapping when the mapping function throws, so a transient failure does not poison the cache.
     *
     * @param queueName configured queue name
     * @return the resolved queue URL
     * @throws QueueDoesNotExistException if the queue is missing and auto-creation is disabled
     */
    private String resolve(String queueName) {
        return queueUrlCache.computeIfAbsent(queueName, name -> {
            try {
                String url = sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl();
                log.info("Resolved SQS queue '{}' to url '{}'", name, url);
                return url;
            } catch (QueueDoesNotExistException e) {
                if (!awsProperties.sqs().autoCreateQueues()) {
                    log.error("SQS queue '{}' does not exist and auto-creation is disabled", name, e);
                    throw e;
                }
                return create(name);
            }
        });
    }

    private String create(String queueName) {
        String url = sqsClient.createQueue(CreateQueueRequest.builder()
                        .queueName(queueName)
                        .attributes(Map.of(
                                QueueAttributeName.VISIBILITY_TIMEOUT,
                                String.valueOf(awsProperties.sqs().visibilityTimeoutSeconds())))
                        .build())
                .queueUrl();
        log.warn("SQS queue '{}' did not exist and was auto-created at '{}' (aws.sqs.auto-create-queues=true)",
                queueName, url);
        return url;
    }
}
