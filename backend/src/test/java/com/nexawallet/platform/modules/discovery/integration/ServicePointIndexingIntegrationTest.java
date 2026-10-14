package com.nexawallet.platform.modules.discovery.integration;

import com.nexawallet.platform.modules.discovery.document.ServicePointDocument;
import com.nexawallet.platform.modules.discovery.dto.ServicePointCreateRequest;
import com.nexawallet.platform.modules.discovery.dto.ServicePointUpdateRequest;
import com.nexawallet.platform.modules.discovery.entity.ServicePointType;
import com.nexawallet.platform.modules.discovery.repository.ServicePointRepository;
import com.nexawallet.platform.modules.discovery.service.ServicePointAdminService;
import com.nexawallet.platform.modules.discovery.service.ServicePointReindexService;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import com.nexawallet.platform.modules.outbox.repository.ProcessedEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end test of the service-point indexing pipeline: admin write → outbox (same DB
 * transaction) → Kafka ({@code servicepoints.index.v1}) → consumer → Elasticsearch upsert —
 * against real PostgreSQL, Kafka, and Elasticsearch via Testcontainers.
 *
 * {@code disabledWithoutDocker = true} reports every test here SKIPPED (not failed) when no
 * Docker daemon is reachable, which is the case in this sandboxed environment.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Service Point Indexing Pipeline — Testcontainers Integration Tests")
class ServicePointIndexingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    static ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.11.1"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @org.springframework.test.context.DynamicPropertySource
    static void registerProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("nexa.outbox.publisher.enabled", () -> "true");
        registry.add("nexa.outbox.publisher.poll-interval-ms", () -> "500");
        registry.add("nexa.kafka.consumer.auto-startup", () -> "true");

        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }

    @Autowired private ServicePointAdminService adminService;
    @Autowired private ServicePointReindexService reindexService;
    @Autowired private ServicePointRepository servicePointRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private ElasticsearchOperations elasticsearchOperations;

    private static NativeQuery matchAll() {
        return NativeQuery.builder().withQuery(Query.of(q -> q.matchAll(m -> m))).build();
    }

    @AfterEach
    void cleanUp() {
        processedEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        servicePointRepository.deleteAll();
        var indexOps = elasticsearchOperations.indexOps(ServicePointDocument.class);
        if (indexOps.exists()) {
            elasticsearchOperations.delete(matchAll(), ServicePointDocument.class);
            indexOps.refresh();
        }
    }

    @Test
    @DisplayName("1. Creating a service point is visible in Elasticsearch once the pipeline processes it")
    void create_isIndexedViaThePipeline() {
        var created = adminService.create(ServicePointCreateRequest.builder()
                .name("Pipeline ATM").type(ServicePointType.ATM).latitude(40.0).longitude(-74.0).build());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ServicePointDocument doc = elasticsearchOperations.get(created.getId().toString(), ServicePointDocument.class);
            assertThat(doc).isNotNull();
            assertThat(doc.getName()).isEqualTo("Pipeline ATM");
            assertThat(doc.isActive()).isTrue();
        });

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("2. An update then deactivation both land, in order, as upserts on the same document")
    void updateThenDeactivate_bothLandInOrder() {
        var created = adminService.create(ServicePointCreateRequest.builder()
                .name("Lifecycle ATM").type(ServicePointType.ATM).latitude(10.0).longitude(10.0).build());

        adminService.update(created.getId(), ServicePointUpdateRequest.builder().name("Renamed ATM").build());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ServicePointDocument doc = elasticsearchOperations.get(created.getId().toString(), ServicePointDocument.class);
            assertThat(doc).isNotNull();
            assertThat(doc.getName()).isEqualTo("Renamed ATM");
        });

        adminService.deactivate(created.getId());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ServicePointDocument doc = elasticsearchOperations.get(created.getId().toString(), ServicePointDocument.class);
            assertThat(doc).isNotNull();
            assertThat(doc.isActive()).isFalse();
        });
    }

    @Test
    @DisplayName("3. Reindexing is repeatable: running it twice never creates duplicate documents for the same service point")
    void reindex_isRepeatableWithoutDuplicates() {
        adminService.create(ServicePointCreateRequest.builder()
                .name("Reindex Target").type(ServicePointType.BRANCH).latitude(20.0).longitude(20.0).build());

        long firstRun = reindexService.reindexAll();
        long secondRun = reindexService.reindexAll();

        assertThat(firstRun).isEqualTo(1);
        assertThat(secondRun).isEqualTo(1);

        var indexOps = elasticsearchOperations.indexOps(ServicePointDocument.class);
        indexOps.refresh();
        long count = elasticsearchOperations.count(elasticsearchOperations.matchAllQuery(), ServicePointDocument.class);
        assertThat(count).isEqualTo(1); // still exactly one logical document, not two
    }
}
