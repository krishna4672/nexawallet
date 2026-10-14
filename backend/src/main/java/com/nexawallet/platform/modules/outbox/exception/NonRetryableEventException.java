package com.nexawallet.platform.modules.outbox.exception;

/**
 * Thrown by a Kafka consumer when a message is structurally unprocessable
 * (malformed JSON, unsupported schema version). The consumer error handler is
 * configured to treat this as non-retryable and route the message straight to
 * the dead-letter topic without burning the normal retry budget — retrying a
 * message that can never parse would only delay every message behind it.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
