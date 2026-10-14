package com.nexawallet.platform.modules.outbox.service;

import com.nexawallet.platform.modules.outbox.config.OutboxPublisherProperties;
import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import com.nexawallet.platform.modules.outbox.kafka.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the scheduled outbox publisher's claim → send → settle flow.
 * Kafka I/O and the database are both mocked; {@link OutboxEventStatusUpdaterTest} and
 * {@link com.nexawallet.platform.modules.outbox.integration.OutboxKafkaIntegrationTest}
 * cover the real backoff math and real-broker behavior respectively.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisherService — Unit Tests")
class OutboxPublisherServiceTest {

    @Mock private OutboxClaimService claimService;
    @Mock private OutboxEventStatusUpdater statusUpdater;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisherProperties properties;
    private OutboxPublisherService publisherService;

    @BeforeEach
    void setUp() {
        properties = new OutboxPublisherProperties();
        properties.setBatchSize(50);
        properties.setSendTimeoutMs(2000);
        publisherService = new OutboxPublisherService(claimService, statusUpdater, kafkaTemplate, properties);
    }

    private OutboxEventEntity pendingEvent() {
        return OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(UUID.randomUUID().toString())
                .eventType("DEPOSIT_SETTLED")
                .payload("{\"eventType\":\"DEPOSIT_SETTLED\"}")
                .status(OutboxStatus.PUBLISHING)
                .build();
    }

    @Test
    @DisplayName("1. Nothing to claim — no Kafka send, no status update")
    void publishPendingEvents_emptyBatch_doesNothing() {
        when(claimService.claimBatch(50)).thenReturn(List.of());

        publisherService.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(statusUpdater, never()).markPublished(any());
        verify(statusUpdater, never()).recordFailure(any(), any());
    }

    @Test
    @DisplayName("2. Broker acknowledgement marks the event PUBLISHED only after the send future completes")
    void publishPendingEvents_ackReceived_marksPublished() {
        OutboxEventEntity event = pendingEvent();
        when(claimService.claimBatch(50)).thenReturn(List.of(event));

        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq(KafkaTopics.TRANSACTIONS), eq(event.getAggregateId()), eq(event.getPayload())))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisherService.publishPendingEvents();

        verify(statusUpdater).markPublished(event.getId());
        verify(statusUpdater, never()).recordFailure(any(), any());
    }

    @Test
    @DisplayName("3. A Kafka publishing failure leaves the event retryable (recordFailure invoked, not markPublished)")
    void publishPendingEvents_sendFails_recordsFailure() {
        OutboxEventEntity event = pendingEvent();
        when(claimService.claimBatch(50)).thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(eq(KafkaTopics.TRANSACTIONS), eq(event.getAggregateId()), eq(event.getPayload())))
                .thenReturn(failed);

        publisherService.publishPendingEvents();

        verify(statusUpdater, never()).markPublished(any());
        verify(statusUpdater).recordFailure(eq(event.getId()), any(Throwable.class));
    }

    @Test
    @DisplayName("4a. A ServicePoint-aggregate event routes to the SERVICE_POINTS topic, not TRANSACTIONS")
    void publishPendingEvents_servicePointAggregate_routesToServicePointsTopic() {
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateType("ServicePoint")
                .aggregateId(UUID.randomUUID().toString())
                .eventType("SERVICE_POINT_CREATED")
                .payload("{}")
                .status(OutboxStatus.PUBLISHING)
                .build();
        when(claimService.claimBatch(50)).thenReturn(List.of(event));

        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq(KafkaTopics.SERVICE_POINTS), eq(event.getAggregateId()), eq(event.getPayload())))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisherService.publishPendingEvents();

        verify(kafkaTemplate).send(eq(KafkaTopics.SERVICE_POINTS), anyString(), anyString());
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.TRANSACTIONS), anyString(), anyString());
        verify(statusUpdater).markPublished(event.getId());
    }

    @Test
    @DisplayName("4. Multiple claimed events are each published independently")
    void publishPendingEvents_multipleEvents_eachPublished() {
        OutboxEventEntity e1 = pendingEvent();
        OutboxEventEntity e2 = pendingEvent();
        when(claimService.claimBatch(50)).thenReturn(List.of(e1, e2));

        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisherService.publishPendingEvents();

        verify(statusUpdater).markPublished(e1.getId());
        verify(statusUpdater).markPublished(e2.getId());
    }
}
