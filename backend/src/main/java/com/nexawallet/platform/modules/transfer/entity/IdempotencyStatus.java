package com.nexawallet.platform.modules.transfer.entity;

/**
 * Lifecycle state of a guarded idempotent operation.
 */
public enum IdempotencyStatus {
    PROCESSING,
    COMPLETED,
    FAILED
}
