package com.nexawallet.platform.common.exception;

/**
 * Thrown when a transfer request is submitted with an idempotency key
 * that has already been successfully processed (safe to return cached response).
 */
public class DuplicateTransferException extends RuntimeException {

    private final String cachedPayload;

    public DuplicateTransferException(String message, String cachedPayload) {
        super(message);
        this.cachedPayload = cachedPayload;
    }

    public String getCachedPayload() {
        return cachedPayload;
    }
}
