package com.nexawallet.platform.modules.outbox.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Operational snapshot of the outbox pipeline for administrative monitoring.
 * Deliberately exposes only counts/ages/metadata — never raw event payloads.
 */
@Getter
@Builder
public class OutboxStatsResponse {
    private long pendingCount;
    private long publishingCount;
    private long publishedCount;
    private long failedCount;
    private Long oldestPendingAgeSeconds;
    private int maxRetryCountAmongPendingOrFailed;
    private long consumerProcessedEventCount;
}
