package com.nexawallet.platform.modules.outbox.service;

import com.nexawallet.platform.modules.outbox.config.OutboxPublisherProperties;
import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the outbox retry/backoff and terminal-failure state machine.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxEventStatusUpdater — Unit Tests")
class OutboxEventStatusUpdaterTest {

    @Mock private OutboxEventRepository outboxEventRepository;

    private OutboxPublisherProperties properties;
    private OutboxEventStatusUpdater updater;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        properties = new OutboxPublisherProperties();
        properties.setMaxRetries(3);
        properties.setBaseBackoffMs(1000);
        properties.setMaxBackoffMs(60_000);
        updater = new OutboxEventStatusUpdater(outboxEventRepository, properties);
        eventId = UUID.randomUUID();
    }

    @Test
    @DisplayName("1. markPublished sets PUBLISHED, stamps publishedAt, and clears lastError")
    void markPublished_setsPublishedState() {
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .status(OutboxStatus.PUBLISHING)
                .lastError("stale error")
                .build();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        updater.markPublished(eventId);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEventEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(saved.getPublishedAt()).isNotNull();
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    @DisplayName("2. A transient failure under the retry budget returns the event to PENDING with backoff scheduled")
    void recordFailure_underBudget_schedulesRetry() {
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .status(OutboxStatus.PUBLISHING)
                .retryCount(0)
                .build();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        updater.recordFailure(eventId, new RuntimeException("broker unavailable"));

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEventEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getNextRetryAt()).isNotNull().isAfter(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        assertThat(saved.getLastError()).contains("RuntimeException").contains("broker unavailable");
    }

    @Test
    @DisplayName("3. Exceeding the configured retry budget moves the event to terminal FAILED")
    void recordFailure_exceedsBudget_movesToFailed() {
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .status(OutboxStatus.PUBLISHING)
                .retryCount(3) // already at maxRetries=3; the 4th failure must terminalize
                .build();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        updater.recordFailure(eventId, new RuntimeException("still down"));

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEventEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(saved.getRetryCount()).isEqualTo(4);
        assertThat(saved.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("4. Sanitized error is stripped of newlines and truncated to 500 characters")
    void sanitize_truncatesAndStripsControlCharacters() {
        String huge = "x".repeat(1000);
        Exception ex = new IllegalStateException("line1\nline2\tline3" + huge);

        String sanitized = OutboxEventStatusUpdater.sanitize(ex);

        assertThat(sanitized).doesNotContain("\n").doesNotContain("\t");
        assertThat(sanitized.length()).isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("5. Backoff grows exponentially and is capped at maxBackoffMs")
    void backoffMillis_growsExponentiallyThenCaps() {
        assertThat(updater.backoffMillis(1)).isEqualTo(1000);
        assertThat(updater.backoffMillis(2)).isEqualTo(2000);
        assertThat(updater.backoffMillis(3)).isEqualTo(4000);
        assertThat(updater.backoffMillis(20)).isEqualTo(60_000); // capped
    }

    @Test
    @DisplayName("6. A missing row (already deleted/claimed elsewhere) is a no-op, not an error")
    void markPublished_missingRow_isNoOp() {
        when(outboxEventRepository.findById(any())).thenReturn(Optional.empty());

        updater.markPublished(eventId);

        verify(outboxEventRepository, org.mockito.Mockito.never()).save(any());
    }
}
