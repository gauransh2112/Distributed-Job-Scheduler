package com.scheduler.job.queue;

import java.util.List;

/**
 * Interface abstraction for SQS message consumption.
 *
 * <p>Receiving and acknowledging are separate operations on purpose. A message must stay on the queue
 * until execution has reached a durable outcome; acknowledging at receive time would turn every worker
 * crash into a silently lost job.
 */
public interface QueueConsumer {

    /**
     * Receives a batch of messages from the main execution queue, deserializing and validating each one.
     *
     * <p>Messages that cannot be deserialized or that violate the message contract are not returned;
     * they are handled by the implementation's invalid-message policy.
     *
     * @return the valid messages received, possibly empty; never null
     */
    List<ReceivedJobMessage> consume();

    /**
     * Acknowledges a message, removing it from the queue so that it is not redelivered.
     *
     * @param message the message whose delivery is being acknowledged
     */
    void acknowledge(ReceivedJobMessage message);
}
