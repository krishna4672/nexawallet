package com.nexawallet.platform.modules.outbox.kafka;

/**
 * Canonical Kafka topic names for the NexaWallet event-driven pipeline.
 *
 * <ul>
 *   <li>{@link #TRANSACTIONS} — every settled financial transaction (deposit or P2P transfer),
 *       keyed by transaction UUID. This is the stream the current {@code TransactionEventConsumer}
 *       reads, and the one the future Elasticsearch indexer (Phase 6) will subscribe to.</li>
 *   <li>{@link #TRANSFERS} — provisioned and reserved for a future dedicated P2P-transfer-only
 *       stream (e.g. fraud/velocity checks, recipient notifications). Not yet published to.</li>
 *   <li>{@link #NOTIFICATIONS} — provisioned and reserved for future outbound user notification
 *       events (email/push/SMS). Not yet published to.</li>
 *   <li>{@link #DLQ} — dead-letter topic. Actively used: the transaction-event consumer routes a
 *       message here after it exhausts its bounded retry budget or fails non-retryable validation
 *       (malformed payload / unsupported schema version).</li>
 * </ul>
 */
public final class KafkaTopics {

    public static final String TRANSFERS = "wallet.transfers.v1";
    public static final String TRANSACTIONS = "wallet.transactions.v1";
    public static final String NOTIFICATIONS = "wallet.notifications.v1";
    public static final String DLQ = "wallet.dlq.v1";

    /**
     * Phase 6: service-point create/update/deactivate events, consumed by
     * {@code ServicePointIndexEventConsumer} to keep the Elasticsearch discovery index in sync
     * with PostgreSQL. Same naming convention as the wallet topics above (`<domain>.<purpose>.v1`).
     * Poison/exhausted-retry messages from this topic are routed to the same {@link #DLQ} as every
     * other consumer in this codebase — see {@code KafkaConsumerConfig}.
     */
    public static final String SERVICE_POINTS = "servicepoints.index.v1";

    private KafkaTopics() {
    }
}
