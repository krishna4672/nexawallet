package com.nexawallet.platform.modules.outbox.entity;

/**
 * Status of transactional outbox event publishing.
 *
 * Lifecycle: PENDING --(claimed by a publisher)--> PUBLISHING --(broker ack)--> PUBLISHED
 *                                                       |--(transient failure, retries remain)--> PENDING (with backoff)
 *                                                       |--(retries exhausted)--> FAILED
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED
}
