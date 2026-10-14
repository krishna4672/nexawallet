package com.nexawallet.platform.modules.discovery.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexawallet.platform.modules.discovery.document.ServicePointDocument;
import com.nexawallet.platform.modules.outbox.dto.OutboxEventPayload;
import com.nexawallet.platform.modules.outbox.entity.ProcessedEventEntity;
import com.nexawallet.platform.modules.outbox.exception.NonRetryableEventException;
import com.nexawallet.platform.modules.outbox.kafka.KafkaTopics;
import com.nexawallet.platform.modules.outbox.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Consumes service-point create/update/deactivate events from {@link KafkaTopics#SERVICE_POINTS}
 * and upserts the corresponding Elasticsearch document.
 *
 * Every event type carries a full current snapshot (see {@code ServicePointAdminService}), so this
 * consumer only ever needs one code path: parse, validate, dedupe, upsert. There is no separate
 * "delete" branch — deactivation is represented as {@code active=false} inside the same upsert,
 * which is what keeps a deactivated point out of {@code ServicePointSearchService}'s results
 * without ever losing the document (useful for a future reactivation).
 *
 * Idempotency, retry, and DLQ routing all reuse exactly the same mechanisms as
 * {@code TransactionEventConsumer} (Phase 4): a {@code processed_events} row per
 * (eventId, consumerGroup) before acknowledging, a {@link NonRetryableEventException} for
 * structurally bad messages, and the shared {@code kafkaListenerContainerFactory}'s
 * {@code DefaultErrorHandler} (bounded exponential backoff, then {@link KafkaTopics#DLQ}) for
 * everything else — including a transient Elasticsearch failure during the upsert itself, which
 * is deliberately left to propagate as a plain exception so the container retries it.
 */
@Component
public class ServicePointIndexEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ServicePointIndexEventConsumer.class);
    private static final String CONSUMER_GROUP = "nexawallet-service-point-indexer";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final ProcessedEventRepository processedEventRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    public ServicePointIndexEventConsumer(ProcessedEventRepository processedEventRepository, ElasticsearchOperations elasticsearchOperations) {
        this.processedEventRepository = processedEventRepository;
        this.elasticsearchOperations = elasticsearchOperations;
    }

    @KafkaListener(
            topics = KafkaTopics.SERVICE_POINTS,
            groupId = CONSUMER_GROUP,
            autoStartup = "${nexa.kafka.consumer.auto-startup:true}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onServicePointEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        OutboxEventPayload payload = parse(record);
        validateSchema(payload);

        if (processedEventRepository.existsByEventIdAndConsumerGroup(payload.getEventId(), CONSUMER_GROUP)) {
            log.info("Duplicate delivery of service-point event {} ignored (already processed by {})", payload.getEventId(), CONSUMER_GROUP);
            acknowledgment.acknowledge();
            return;
        }

        ServicePointDocument document = toDocument(payload.getData());
        elasticsearchOperations.save(document); // upsert by _id — safe under redelivery/retry

        try {
            processedEventRepository.save(ProcessedEventEntity.builder()
                    .eventId(payload.getEventId())
                    .consumerGroup(CONSUMER_GROUP)
                    .topic(record.topic())
                    .build());
        } catch (DataIntegrityViolationException raceLoser) {
            log.debug("Processed-event race for {}: already recorded by a concurrent consumer", payload.getEventId());
        }

        log.info("Indexed service-point event {} type={} servicePointId={} (schema v{})",
                payload.getEventId(), payload.getEventType(), payload.getAggregateId(), payload.getSchemaVersion());

        acknowledgment.acknowledge();
    }

    private OutboxEventPayload parse(ConsumerRecord<String, String> record) {
        try {
            return MAPPER.readValue(record.value(), OutboxEventPayload.class);
        } catch (Exception malformed) {
            throw new NonRetryableEventException(
                    "Malformed service-point event at " + record.topic() + "-" + record.partition() + "@" + record.offset(),
                    malformed);
        }
    }

    private void validateSchema(OutboxEventPayload payload) {
        if (payload.getEventId() == null || payload.getEventType() == null || payload.getData() == null) {
            throw new NonRetryableEventException("Service-point event missing required fields (eventId/eventType/data)");
        }
        if (payload.getSchemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableEventException(
                    "Unsupported schema version " + payload.getSchemaVersion() + " for event " + payload.getEventId());
        }
    }

    private ServicePointDocument toDocument(Map<String, Object> data) {
        try {
            String id = String.valueOf(data.get("servicePointId"));
            double latitude = asDouble(data.get("latitude"));
            double longitude = asDouble(data.get("longitude"));
            Object updatedAtRaw = data.get("updatedAt");

            return ServicePointDocument.builder()
                    .id(id)
                    .name((String) data.get("name"))
                    .type((String) data.get("type"))
                    .active(Boolean.TRUE.equals(data.get("active")))
                    .location(new GeoPoint(latitude, longitude))
                    .geohash((String) data.get("geohash"))
                    .address((String) data.get("address"))
                    .updatedAt(updatedAtRaw != null ? OffsetDateTime.parse(updatedAtRaw.toString()) : OffsetDateTime.now())
                    .build();
        } catch (Exception malformedData) {
            throw new NonRetryableEventException("Service-point event payload data could not be mapped to a document", malformedData);
        }
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
