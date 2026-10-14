package com.nexawallet.platform.modules.outbox.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexawallet.platform.modules.outbox.dto.OutboxEventPayload;
import com.nexawallet.platform.modules.outbox.entity.ProcessedEventEntity;
import com.nexawallet.platform.modules.outbox.exception.NonRetryableEventException;
import com.nexawallet.platform.modules.outbox.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes settled-transaction domain events from {@link KafkaTopics#TRANSACTIONS}.
 *
 * This phase only needs a durable, idempotent projection point to prepare the event
 * flow for the later Elasticsearch indexing phase — it does not index anything yet.
 * Each successfully-handled event is recorded in {@code processed_events} (a unique
 * constraint on (event_id, consumer_group)) before the offset is acknowledged, so a
 * redelivery of the same message — which Kafka's at-least-once delivery guarantees
 * can produce after a rebalance or a consumer crash — is detected and skipped rather
 * than re-applied.
 *
 * No credentials or tokens ever appear in this payload (see {@link com.nexawallet.platform.modules.outbox.service.OutboxEventWriter}),
 * and log statements below only ever reference the event id / type / aggregate id —
 * never full payload contents.
 */
@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);
    private static final String CONSUMER_GROUP = "nexawallet-transaction-indexer";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final ProcessedEventRepository processedEventRepository;

    public TransactionEventConsumer(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @KafkaListener(
            topics = KafkaTopics.TRANSACTIONS,
            groupId = CONSUMER_GROUP,
            autoStartup = "${nexa.kafka.consumer.auto-startup:true}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTransactionEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        OutboxEventPayload payload = parse(record);
        validateSchema(payload);

        if (processedEventRepository.existsByEventIdAndConsumerGroup(payload.getEventId(), CONSUMER_GROUP)) {
            log.info("Duplicate delivery of event {} ignored (already processed by {})", payload.getEventId(), CONSUMER_GROUP);
            acknowledgment.acknowledge();
            return;
        }

        try {
            processedEventRepository.save(ProcessedEventEntity.builder()
                    .eventId(payload.getEventId())
                    .consumerGroup(CONSUMER_GROUP)
                    .topic(record.topic())
                    .build());
        } catch (DataIntegrityViolationException raceLoser) {
            // Another instance/thread recorded this event first — already handled, not an error.
            log.debug("Processed-event race for {}: already recorded by a concurrent consumer", payload.getEventId());
        }

        log.info("Indexed transaction event {} type={} aggregateId={} (schema v{})",
                payload.getEventId(), payload.getEventType(), payload.getAggregateId(), payload.getSchemaVersion());

        acknowledgment.acknowledge();
    }

    private OutboxEventPayload parse(ConsumerRecord<String, String> record) {
        try {
            return MAPPER.readValue(record.value(), OutboxEventPayload.class);
        } catch (Exception malformed) {
            throw new NonRetryableEventException(
                    "Malformed transaction event at " + record.topic() + "-" + record.partition() + "@" + record.offset(),
                    malformed);
        }
    }

    private void validateSchema(OutboxEventPayload payload) {
        if (payload.getEventId() == null || payload.getEventType() == null) {
            throw new NonRetryableEventException("Transaction event missing required fields (eventId/eventType)");
        }
        if (payload.getSchemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableEventException(
                    "Unsupported schema version " + payload.getSchemaVersion() + " for event " + payload.getEventId());
        }
    }
}
