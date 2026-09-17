package com.scheduler.job.queue;

import java.util.Objects;

/**
 * A deserialized {@link JobMessage} together with the SQS delivery metadata needed to acknowledge it.
 *
 * <p>The receipt handle is delivery-scoped, not message-scoped: it is only valid for this particular
 * receive, which is why it travels with the message rather than being derived later.
 *
 * @param messageId     SQS message identifier, used for log correlation
 * @param receiptHandle handle required to delete (acknowledge) this delivery
 * @param message       the deserialized and validated job message
 */
public record ReceivedJobMessage(
        String messageId,
        String receiptHandle,
        JobMessage message
) {

    public ReceivedJobMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(receiptHandle, "receiptHandle must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
