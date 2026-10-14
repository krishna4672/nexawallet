package com.nexawallet.platform.modules.outbox.service;

import com.nexawallet.platform.modules.outbox.dto.OutboxStatsResponse;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import com.nexawallet.platform.modules.outbox.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Computes the operational snapshot exposed by the admin-only outbox endpoint.
 */
@Service
public class OutboxMetricsService {

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;

    public OutboxMetricsService(OutboxEventRepository outboxEventRepository, ProcessedEventRepository processedEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional(readOnly = true)
    public OutboxStatsResponse snapshot() {
        long pending = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        long publishing = outboxEventRepository.countByStatus(OutboxStatus.PUBLISHING);
        long published = outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED);
        long failed = outboxEventRepository.countByStatus(OutboxStatus.FAILED);

        Long oldestPendingAgeSeconds = outboxEventRepository.findOldestCreatedAtByStatus(OutboxStatus.PENDING)
                .map(createdAt -> Duration.between(createdAt, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds())
                .orElse(null);

        int maxRetry = outboxEventRepository
                .findMaxRetryCountByStatusIn(List.of(OutboxStatus.PENDING, OutboxStatus.FAILED))
                .orElse(0);

        return OutboxStatsResponse.builder()
                .pendingCount(pending)
                .publishingCount(publishing)
                .publishedCount(published)
                .failedCount(failed)
                .oldestPendingAgeSeconds(oldestPendingAgeSeconds)
                .maxRetryCountAmongPendingOrFailed(maxRetry)
                .consumerProcessedEventCount(processedEventRepository.count())
                .build();
    }
}
