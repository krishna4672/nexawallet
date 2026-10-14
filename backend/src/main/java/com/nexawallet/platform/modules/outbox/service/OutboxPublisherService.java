package com.nexawallet.platform.modules.outbox.service;

import com.nexawallet.platform.modules.outbox.config.OutboxPublisherProperties;
import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.kafka.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled transactional-outbox publisher.
 *
 * Every poll cycle: claim a bounded batch of eligible PENDING rows (see
 * {@link OutboxClaimService}), then — with no DB transaction open — publish each
 * one to Kafka and block for the broker acknowledgement. A row is marked PUBLISHED
 * only after that acknowledgement arrives; any other outcome (timeout, broker
 * error, serialization failure) is treated as a transient failure and scheduled
 * for bounded-exponential-backoff retry via {@link OutboxEventStatusUpdater},
 * until the configured retry budget is exhausted and the row becomes FAILED.
 *
 * This is at-least-once delivery, not exactly-once: if this process crashes after
 * Kafka has acknowledged a send but before the PUBLISHED status commits, the same
 * event will be retried and delivered again on restart. Consumers are expected to
 * dedupe (see {@code TransactionEventConsumer}).
 */
@Service
@ConditionalOnProperty(prefix = "nexa.outbox.publisher", name = "enabled", matchIfMissing = true)
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxClaimService claimService;
    private final OutboxEventStatusUpdater statusUpdater;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxPublisherProperties properties;

    public OutboxPublisherService(
            OutboxClaimService claimService,
            OutboxEventStatusUpdater statusUpdater,
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxPublisherProperties properties
    ) {
        this.claimService = claimService;
        this.statusUpdater = statusUpdater;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${nexa.outbox.publisher.poll-interval-ms:2000}")
    public void publishPendingEvents() {
        List<OutboxEventEntity> claimed = claimService.claimBatch(properties.getBatchSize());
        if (claimed.isEmpty()) {
            return;
        }
        log.debug("Claimed {} pending outbox event(s) for publishing", claimed.size());
        for (OutboxEventEntity event : claimed) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEventEntity event) {
        String topic = resolveTopic(event);
        try {
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                    .get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            statusUpdater.markPublished(event.getId());
            log.info("Published outbox event {} ({}) to topic {}", event.getId(), event.getEventType(), topic);
        } catch (Exception ex) {
            statusUpdater.recordFailure(event.getId(), ex);
        }
    }

    /**
     * Routes by aggregate type: every settled-transaction event (deposit or transfer) goes to
     * {@link KafkaTopics#TRANSACTIONS} — the stream the transaction-event consumer reads and that
     * the Elasticsearch transaction-search indexer (a later phase) will subscribe to — and every
     * service-point event (Phase 6) goes to {@link KafkaTopics#SERVICE_POINTS}, read by
     * {@code ServicePointIndexEventConsumer} to keep the discovery search index in sync.
     * {@link KafkaTopics#TRANSFERS} and {@link KafkaTopics#NOTIFICATIONS} remain provisioned but
     * reserved for later consumers.
     *
     * Partition key = the aggregate's own UUID (set by {@code OutboxEventWriter}): this guarantees
     * at most one in-flight message per aggregate, and — since Kafka preserves order within a
     * partition — that two events for the *same* transaction or service point are always delivered
     * in the order they were written. There is no ordering guarantee across different aggregates
     * (different transactions, or different service points), which is fine: every event is a
     * complete, self-contained, idempotent snapshot of its own aggregate's current state.
     */
    private String resolveTopic(OutboxEventEntity event) {
        return "ServicePoint".equals(event.getAggregateType()) ? KafkaTopics.SERVICE_POINTS : KafkaTopics.TRANSACTIONS;
    }
}
