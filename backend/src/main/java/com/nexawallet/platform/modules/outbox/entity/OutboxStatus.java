package com.nexawallet.platform.modules.outbox.entity;

/**
 * Status of transactional outbox event publishing.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
