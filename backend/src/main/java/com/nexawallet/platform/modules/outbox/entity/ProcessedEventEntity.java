package com.nexawallet.platform.modules.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Durable, consumer-side idempotency registry.
 *
 * Kafka delivers at-least-once: the same message may be redelivered after a
 * consumer crash, a rebalance, or a broker retry. Recording the (eventId, consumerGroup)
 * pair here — behind a unique constraint — lets a consumer safely detect and skip a
 * duplicate delivery without re-applying its side effects.
 */
@Entity
@Table(name = "processed_events",
        uniqueConstraints = @UniqueConstraint(name = "uq_processed_events_event_consumer", columnNames = {"event_id", "consumer_group"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "topic", nullable = false, length = 150)
    private String topic;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private OffsetDateTime processedAt;

    @PrePersist
    protected void onCreate() {
        if (this.processedAt == null) {
            this.processedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
