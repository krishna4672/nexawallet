package com.nexawallet.platform.modules.outbox.service;

import com.nexawallet.platform.modules.outbox.config.OutboxPublisherProperties;
import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Applies the post-Kafka-attempt outcome to an outbox row, each in its own short
 * transaction — deliberately separate from {@link OutboxClaimService}'s claim
 * transaction so no DB lock is ever held across the Kafka network call.
 */
@Service
public class OutboxEventStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventStatusUpdater.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPublisherProperties properties;

    public OutboxEventStatusUpdater(OutboxEventRepository outboxEventRepository, OutboxPublisherProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
    }

    /** Broker acknowledged the send — mark the row PUBLISHED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            event.setLastError(null);
            outboxEventRepository.save(event);
        });
    }

    /**
     * Send failed (timeout, broker unavailable, serialization error, etc.). If retries
     * remain, schedules the next attempt with bounded exponential backoff and returns the
     * row to PENDING so the next poll cycle can pick it back up; otherwise moves it to the
     * terminal FAILED state with a sanitized, length-limited diagnostic.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID eventId, Throwable error) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            int attempt = event.getRetryCount() + 1;
            event.setRetryCount(attempt);
            event.setLastError(sanitize(error));

            if (attempt > properties.getMaxRetries()) {
                event.setStatus(OutboxStatus.FAILED);
                event.setNextRetryAt(null);
                log.error("Outbox event {} moved to FAILED after {} attempts: {}", eventId, attempt, event.getLastError());
            } else {
                event.setStatus(OutboxStatus.PENDING);
                event.setNextRetryAt(OffsetDateTime.now(ZoneOffset.UTC).plusNanos(backoffMillis(attempt) * 1_000_000L));
                log.warn("Outbox event {} publish attempt {} failed, retrying at {}: {}",
                        eventId, attempt, event.getNextRetryAt(), event.getLastError());
            }
            outboxEventRepository.save(event);
        });
    }

    /** Bounded exponential backoff: base * 2^(attempt-1), capped at maxBackoffMs. */
    long backoffMillis(int attempt) {
        long exponential = properties.getBaseBackoffMs() * (1L << Math.min(attempt - 1, 20));
        return Math.min(exponential, properties.getMaxBackoffMs());
    }

    /** Strips control characters and truncates so a stack trace can never flood the column or logs. */
    static String sanitize(Throwable error) {
        String raw = error.getClass().getSimpleName() + ": " + error.getMessage();
        String cleaned = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }
}
