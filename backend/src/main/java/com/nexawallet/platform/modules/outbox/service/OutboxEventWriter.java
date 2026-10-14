package com.nexawallet.platform.modules.outbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexawallet.platform.modules.outbox.dto.OutboxEventPayload;
import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Writes outbox rows as part of the caller's existing database transaction.
 *
 * This class never talks to Kafka. It only persists a row via the regular
 * {@link OutboxEventRepository#save(Object)} JPA call, so if the enclosing
 * {@code @Transactional} financial operation (deposit, transfer) rolls back,
 * the outbox row rolls back with it — there is no separate transaction here
 * to create a dual-write hazard.
 */
@Service
public class OutboxEventWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventWriter.class);
    private static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxEventWriter(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Appends a versioned domain event to the outbox, in the caller's current transaction.
     *
     * @param aggregateType logical aggregate name (e.g. "Transaction")
     * @param aggregateId   id of the aggregate the event describes
     * @param eventType     business event type (e.g. "P2P_TRANSFER_SETTLED", "DEPOSIT_SETTLED")
     * @param data          event-specific business metadata only — no credentials, no JWTs,
     *                      no unnecessary personal data
     */
    public void record(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> data) {
        OutboxEventPayload payload = OutboxEventPayload.builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .schemaVersion(SCHEMA_VERSION)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId.toString())
                .occurredAt(OffsetDateTime.now(ZoneOffset.UTC))
                .data(data)
                .build();

        OutboxEventEntity entity = OutboxEventEntity.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId.toString())
                .eventType(eventType)
                .payload(serialize(payload))
                .status(OutboxStatus.PENDING)
                .build();

        outboxEventRepository.save(entity);
        log.debug("Recorded outbox event {} ({}) for aggregate {}/{}", payload.getEventId(), eventType, aggregateType, aggregateId);
    }

    private String serialize(OutboxEventPayload payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            // Serialization of our own well-typed envelope should never fail; if it somehow
            // does, surfacing the failure aborts the enclosing financial transaction rather
            // than silently losing the event.
            throw new IllegalStateException("Failed to serialize outbox event payload for event type " + payload.getEventType(), e);
        }
    }
}
