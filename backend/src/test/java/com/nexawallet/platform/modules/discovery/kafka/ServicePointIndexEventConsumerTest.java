package com.nexawallet.platform.modules.discovery.kafka;

import com.nexawallet.platform.modules.discovery.document.ServicePointDocument;
import com.nexawallet.platform.modules.outbox.entity.ProcessedEventEntity;
import com.nexawallet.platform.modules.outbox.exception.NonRetryableEventException;
import com.nexawallet.platform.modules.outbox.kafka.KafkaTopics;
import com.nexawallet.platform.modules.outbox.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServicePointIndexEventConsumer — Unit Tests")
class ServicePointIndexEventConsumerTest {

    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private ElasticsearchOperations elasticsearchOperations;
    @Mock private Acknowledgment acknowledgment;

    private ServicePointIndexEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ServicePointIndexEventConsumer(processedEventRepository, elasticsearchOperations);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(KafkaTopics.SERVICE_POINTS, 0, 7L, "key", value);
    }

    private String validPayload(UUID eventId, UUID servicePointId, boolean active) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"SERVICE_POINT_CREATED\",\"schemaVersion\":1,"
                + "\"aggregateType\":\"ServicePoint\",\"aggregateId\":\"" + servicePointId + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"data\":{\"servicePointId\":\"" + servicePointId + "\",\"name\":\"Test ATM\",\"type\":\"ATM\","
                + "\"latitude\":40.0,\"longitude\":-73.0,\"geohash\":\"dr5reg\",\"address\":\"123 Main St\","
                + "\"active\":" + active + ",\"updatedAt\":\"2026-01-01T00:00:00Z\"}}";
    }

    @Test
    @DisplayName("1. A valid event upserts the document into Elasticsearch, records it as processed, and acknowledges")
    void onServicePointEvent_validEvent_upsertsAndAcknowledges() {
        UUID eventId = UUID.randomUUID();
        UUID servicePointId = UUID.randomUUID();
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "nexawallet-service-point-indexer")).thenReturn(false);

        consumer.onServicePointEvent(record(validPayload(eventId, servicePointId, true)), acknowledgment);

        ArgumentCaptor<ServicePointDocument> docCaptor = ArgumentCaptor.forClass(ServicePointDocument.class);
        verify(elasticsearchOperations).save(docCaptor.capture());
        assertThat(docCaptor.getValue().getId()).isEqualTo(servicePointId.toString());
        assertThat(docCaptor.getValue().isActive()).isTrue();
        assertThat(docCaptor.getValue().getLocation().getLat()).isEqualTo(40.0);

        verify(processedEventRepository).save(any(ProcessedEventEntity.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("2. A deactivation event (active=false) still upserts — never deletes — the document")
    void onServicePointEvent_deactivation_upsertsWithActiveFalse() {
        UUID eventId = UUID.randomUUID();
        UUID servicePointId = UUID.randomUUID();
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "nexawallet-service-point-indexer")).thenReturn(false);

        consumer.onServicePointEvent(record(validPayload(eventId, servicePointId, false)), acknowledgment);

        ArgumentCaptor<ServicePointDocument> docCaptor = ArgumentCaptor.forClass(ServicePointDocument.class);
        verify(elasticsearchOperations).save(docCaptor.capture());
        assertThat(docCaptor.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("3. Duplicate delivery of an already-processed event skips the Elasticsearch write, still acknowledges")
    void onServicePointEvent_duplicate_skipsWrite() {
        UUID eventId = UUID.randomUUID();
        UUID servicePointId = UUID.randomUUID();
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "nexawallet-service-point-indexer")).thenReturn(true);

        consumer.onServicePointEvent(record(validPayload(eventId, servicePointId, true)), acknowledgment);

        verify(elasticsearchOperations, never()).save(any(ServicePointDocument.class));
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    @DisplayName("4. Malformed JSON is classified non-retryable")
    void onServicePointEvent_malformedJson_throwsNonRetryable() {
        assertThatThrownBy(() -> consumer.onServicePointEvent(record("{not-json"), acknowledgment))
                .isInstanceOf(NonRetryableEventException.class);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("5. An unsupported schema version is classified non-retryable")
    void onServicePointEvent_unsupportedSchemaVersion_throwsNonRetryable() {
        UUID eventId = UUID.randomUUID();
        UUID servicePointId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"SERVICE_POINT_CREATED\",\"schemaVersion\":99,"
                + "\"aggregateType\":\"ServicePoint\",\"aggregateId\":\"" + servicePointId + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"data\":{\"servicePointId\":\"" + servicePointId + "\"}}";

        assertThatThrownBy(() -> consumer.onServicePointEvent(record(payload), acknowledgment))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("Unsupported schema version");
        verify(elasticsearchOperations, never()).save(any(ServicePointDocument.class));
    }

    @Test
    @DisplayName("6. A transient Elasticsearch failure during the upsert propagates (left to the container's retry/DLQ policy)")
    void onServicePointEvent_elasticsearchThrows_propagates() {
        UUID eventId = UUID.randomUUID();
        UUID servicePointId = UUID.randomUUID();
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "nexawallet-service-point-indexer")).thenReturn(false);
        when(elasticsearchOperations.save(any(ServicePointDocument.class))).thenThrow(new RuntimeException("ES unavailable"));

        assertThatThrownBy(() -> consumer.onServicePointEvent(record(validPayload(eventId, servicePointId, true)), acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(acknowledgment, never()).acknowledge();
        verify(processedEventRepository, never()).save(any(ProcessedEventEntity.class));
    }
}
