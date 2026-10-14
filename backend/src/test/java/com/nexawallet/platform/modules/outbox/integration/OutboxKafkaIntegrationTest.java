package com.nexawallet.platform.modules.outbox.integration;

import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import com.nexawallet.platform.modules.outbox.entity.ProcessedEventEntity;
import com.nexawallet.platform.modules.outbox.kafka.KafkaTopics;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import com.nexawallet.platform.modules.outbox.repository.ProcessedEventRepository;
import com.nexawallet.platform.modules.outbox.service.OutboxClaimService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end test of the outbox → Kafka → consumer pipeline against real
 * PostgreSQL and Kafka brokers via Testcontainers.
 *
 * {@code @Testcontainers(disabledWithoutDocker = true)} makes every test in this
 * class report SKIPPED (not failed) when no Docker daemon is reachable, which is
 * the case in this sandboxed environment (Docker CLI present, daemon not running).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Outbox + Kafka — Testcontainers Integration Tests")
class OutboxKafkaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("nexa.outbox.publisher.enabled", () -> "true");
        registry.add("nexa.outbox.publisher.poll-interval-ms", () -> "500");
        registry.add("nexa.outbox.publisher.max-retries", () -> "2");
        registry.add("nexa.outbox.publisher.base-backoff-ms", () -> "200");
        registry.add("nexa.kafka.consumer.auto-startup", () -> "true");
        registry.add("nexa.kafka.consumer.max-retries", () -> "2");
        registry.add("nexa.kafka.consumer.retry-backoff-ms", () -> "200");
    }

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private OutboxClaimService claimService;

    @AfterEach
    void cleanUp() {
        processedEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Concurrent claim correctness
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Two concurrent publisher instances never claim the same PENDING row")
    void concurrentClaims_neverOverlap() throws Exception {
        for (int i = 0; i < 20; i++) {
            outboxEventRepository.save(OutboxEventEntity.builder()
                    .aggregateType("Transaction")
                    .aggregateId(UUID.randomUUID().toString())
                    .eventType("DEPOSIT_SETTLED")
                    .payload("{}")
                    .status(OutboxStatus.PENDING)
                    .build());
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CompletableFuture<List<OutboxEventEntity>> claimA = CompletableFuture.supplyAsync(() -> claimService.claimBatch(10), pool);
        CompletableFuture<List<OutboxEventEntity>> claimB = CompletableFuture.supplyAsync(() -> claimService.claimBatch(10), pool);
        List<OutboxEventEntity> batchA = claimA.get(10, TimeUnit.SECONDS);
        List<OutboxEventEntity> batchB = claimB.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        List<UUID> idsA = batchA.stream().map(OutboxEventEntity::getId).toList();
        List<UUID> idsB = batchB.stream().map(OutboxEventEntity::getId).toList();

        assertThat(idsA).doesNotContainAnyElementsOf(idsB);
        assertThat(idsA.size() + idsB.size()).isEqualTo(20);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Full pipeline: publish → broker ack → consume → dedupe
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2. An outbox row reaches PUBLISHED only after real broker acknowledgement, and the consumer indexes it exactly once")
    void outboxRow_publishesAndIsConsumedExactlyOnce() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEventEntity saved = outboxEventRepository.save(OutboxEventEntity.builder()
                .aggregateType("Transaction")
                .aggregateId(aggregateId.toString())
                .eventType("DEPOSIT_SETTLED")
                .payload("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"DEPOSIT_SETTLED\",\"schemaVersion\":1,"
                        + "\"aggregateType\":\"Transaction\",\"aggregateId\":\"" + aggregateId + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"data\":{}}")
                .status(OutboxStatus.PENDING)
                .build());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            OutboxEventEntity refreshed = outboxEventRepository.findById(saved.getId()).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(refreshed.getPublishedAt()).isNotNull();
        });

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(processedEventRepository.count()).isEqualTo(1));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Duplicate Kafka delivery does not duplicate consumer-side effects
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3. Re-delivering the exact same event (same eventId) twice on the topic does not create a duplicate processed-event row")
    void duplicateDelivery_doesNotDuplicateProcessedEvent() {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"DEPOSIT_SETTLED\",\"schemaVersion\":1,"
                + "\"aggregateType\":\"Transaction\",\"aggregateId\":\"agg-dup\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"data\":{}}";

        try (KafkaProducer<String, String> producer = rawProducer()) {
            producer.send(new ProducerRecord<>(KafkaTopics.TRANSACTIONS, "agg-dup", payload));
            producer.send(new ProducerRecord<>(KafkaTopics.TRANSACTIONS, "agg-dup", payload));
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(processedEventRepository.findAll().stream()
                        .filter(e -> e.getEventId().equals(eventId)).count())
                        .isEqualTo(1));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Poison message routed to DLQ after bounded retries
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("4. A malformed (poison) message is routed to wallet.dlq.v1 with original-topic metadata preserved")
    void poisonMessage_routedToDlq() {
        try (KafkaProducer<String, String> producer = rawProducer()) {
            producer.send(new ProducerRecord<>(KafkaTopics.TRANSACTIONS, "poison-key", "{not-valid-json"));
            producer.flush();
        }

        try (KafkaConsumer<String, String> dlqConsumer = rawConsumer("dlq-test-" + UUID.randomUUID())) {
            dlqConsumer.subscribe(List.of(KafkaTopics.DLQ));
            long deadline = System.currentTimeMillis() + 20_000;
            ConsumerRecord<String, String> found = null;
            while (System.currentTimeMillis() < deadline && found == null) {
                ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if ("poison-key".equals(record.key())) {
                        found = record;
                        break;
                    }
                }
            }
            assertThat(found).as("poison message should have been routed to the DLQ").isNotNull();
            assertThat(found.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
        }
    }

    private KafkaProducer<String, String> rawProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(props);
    }

    private KafkaConsumer<String, String> rawConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }
}
