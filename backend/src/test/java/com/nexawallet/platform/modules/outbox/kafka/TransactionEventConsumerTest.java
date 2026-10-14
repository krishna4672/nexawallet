package com.nexawallet.platform.modules.outbox.kafka;

import com.nexawallet.platform.modules.outbox.entity.ProcessedEventEntity;
import com.nexawallet.platform.modules.outbox.exception.NonRetryableEventException;
import com.nexawallet.platform.modules.outbox.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the transaction-event consumer's validation, idempotency, and
 * error-classification behavior. The actual retry-then-DLQ routing is performed by
 * Spring Kafka's {@code DefaultErrorHandler}/{@code DeadLetterPublishingRecoverer}
 * (configured in {@code KafkaConsumerConfig}) based on the exception types thrown
 * here, and is exercised end-to-end in {@code OutboxKafkaIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionEventConsumer — Unit Tests")
class TransactionEventConsumerTest {

    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private Acknowledgment acknowledgment;

    private TransactionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TransactionEventConsumer(processedEventRepository);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(KafkaTopics.TRANSACTIONS, 0, 42L, "key", value);
    }

    private String validPayload(UUID eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"DEPOSIT_SETTLED\",\"schemaVersion\":1,"
                + "\"aggregateType\":\"Transaction\",\"aggregateId\":\"agg-1\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"data\":{}}";
    }

    @Test
    @DisplayName("1. A well-formed, first-seen event is recorded and acknowledged")
    void onTransactionEvent_newEvent_recordsAndAcknowledges() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "nexawallet-transaction-indexer"))
                .thenReturn(false);

        consumer.onTransactionEvent(record(validPayload(eventId)), acknowledgment);

        verify(processedEventRepository).save(any(ProcessedEventEntity.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("2. Duplicate delivery of an already-processed event is skipped, not re-applied, and still acknowledged")
    void onTransactionEvent_duplicateDelivery_skipsSideEffect() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "nexawallet-transaction-indexer"))
                .thenReturn(true);

        consumer.onTransactionEvent(record(validPayload(eventId)), acknowledgment);

        verify(processedEventRepository, never()).save(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("3. A race where two instances insert the same event concurrently is tolerated, not surfaced as an error")
    void onTransactionEvent_concurrentInsertRace_isTolerated() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "nexawallet-transaction-indexer"))
                .thenReturn(false);
        when(processedEventRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        consumer.onTransactionEvent(record(validPayload(eventId)), acknowledgment);

        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    @DisplayName("4. Malformed JSON is classified non-retryable (routed straight to the DLQ, not retried)")
    void onTransactionEvent_malformedJson_throwsNonRetryable() {
        assertThatThrownBy(() -> consumer.onTransactionEvent(record("{not-json"), acknowledgment))
                .isInstanceOf(NonRetryableEventException.class);

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("5. An unsupported schema version is classified non-retryable")
    void onTransactionEvent_unsupportedSchemaVersion_throwsNonRetryable() {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"DEPOSIT_SETTLED\",\"schemaVersion\":99,"
                + "\"aggregateType\":\"Transaction\",\"aggregateId\":\"agg-1\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"data\":{}}";

        assertThatThrownBy(() -> consumer.onTransactionEvent(record(payload), acknowledgment))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("Unsupported schema version");

        verify(processedEventRepository, never()).save(any());
        verify(acknowledgment, never()).acknowledge();
    }
}
