package com.nexawallet.platform.modules.outbox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned domain event envelope serialized into {@code outbox_events.payload} and
 * published verbatim to Kafka.
 *
 * Deliberately excludes passwords, JWTs, and any unnecessary personal data — only the
 * business metadata a downstream consumer (e.g. the future transaction search indexer)
 * needs to project the event.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutboxEventPayload {

    private UUID eventId;
    private String eventType;
    private int schemaVersion;
    private String aggregateType;
    private String aggregateId;
    private OffsetDateTime occurredAt;
    private Map<String, Object> data;
}
