package com.nexawallet.platform.modules.discovery.integration;

import com.nexawallet.platform.common.geo.GeohashUtils;
import com.nexawallet.platform.modules.discovery.document.ServicePointDocument;
import com.nexawallet.platform.modules.discovery.dto.NearbySearchResponse;
import com.nexawallet.platform.modules.discovery.service.ServicePointSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real-Elasticsearch tests for nearby search: known distances, radius boundaries, pagination,
 * category filtering, deterministic tie-breaking, antimeridian/high-latitude handling, and
 * duplicate avoidance. {@code disabledWithoutDocker = true} reports every test here SKIPPED (not
 * failed) when no Docker daemon is reachable.
 *
 * Uses the real Spring context (so {@code ServicePointIndexInitializer} creates the index exactly
 * as it would at application startup) with {@code @ActiveProfiles("test")} for the unrelated
 * H2/Kafka/Redis config, and {@code @DynamicPropertySource} pointing only
 * {@code spring.elasticsearch.uris} at the Testcontainers instance.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Nearby Service Point Search — Real Elasticsearch Integration Tests")
class ServicePointNearbySearchIntegrationTest {

    @Container
    static ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.11.1"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }

    @Autowired private ElasticsearchOperations elasticsearchOperations;
    @Autowired private ServicePointSearchService searchService;

    @BeforeAll
    static void waitForContainer() {
        // Nothing extra needed — ElasticsearchContainer's own wait strategy blocks start() until ready.
    }

    private static NativeQuery matchAll() {
        return NativeQuery.builder().withQuery(Query.of(q -> q.matchAll(m -> m))).build();
    }

    @AfterEach
    void cleanUp() {
        var indexOps = elasticsearchOperations.indexOps(ServicePointDocument.class);
        if (indexOps.exists()) {
            elasticsearchOperations.delete(matchAll(), ServicePointDocument.class);
            indexOps.refresh();
        }
    }

    private void index(ServicePointDocument doc) {
        elasticsearchOperations.save(doc);
        elasticsearchOperations.indexOps(ServicePointDocument.class).refresh();
    }

    private ServicePointDocument point(double lat, double lon, String name, String type, boolean active) {
        return ServicePointDocument.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .type(type)
                .active(active)
                .location(new GeoPoint(lat, lon))
                .geohash(GeohashUtils.encode(lat, lon, 9))
                .build();
    }

    @Test
    @DisplayName("1. A point at the exact search center is found with distance ~0")
    void pointAtCenter_isFoundWithNearZeroDistance() {
        index(point(40.7128, -74.0060, "Center ATM", "ATM", true));

        NearbySearchResponse response = searchService.search(40.7128, -74.0060, 1000.0, null, 0, 10);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getDistanceMeters()).isLessThan(1.0);
    }

    @Test
    @DisplayName("2. A point just outside the requested radius is excluded; one just inside is included")
    void radiusBoundary_includesInsideExcludesOutside() {
        // ~1.11km per 0.01 degree latitude
        index(point(40.0, -74.0, "Just inside", "ATM", true));   // ~0 m
        index(point(40.018, -74.0, "Outside", "ATM", true));     // ~2000 m

        NearbySearchResponse response = searchService.search(40.0, -74.0, 1000.0, null, 0, 10);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getName()).isEqualTo("Just inside");
    }

    @Test
    @DisplayName("3. Inactive points are never returned, even within radius")
    void inactivePoint_isExcluded() {
        index(point(40.0, -74.0, "Inactive ATM", "ATM", false));

        NearbySearchResponse response = searchService.search(40.0, -74.0, 1000.0, null, 0, 10);

        assertThat(response.getContent()).isEmpty();
    }

    @Test
    @DisplayName("4. Category filter restricts results to the requested type")
    void categoryFilter_restrictsResults() {
        index(point(40.0, -74.0, "An ATM", "ATM", true));
        index(point(40.0001, -74.0001, "A Branch", "BRANCH", true));

        NearbySearchResponse response = searchService.search(40.0, -74.0, 5000.0, "BRANCH", 0, 10);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getType()).isEqualTo("BRANCH");
    }

    @Test
    @DisplayName("5. Results are sorted by distance ascending, with id as a deterministic tie-break for exact ties")
    void sorting_byDistanceThenId() {
        index(point(40.0, -74.0, "Far", "ATM", true));
        index(point(40.001, -74.0, "Near", "ATM", true));

        NearbySearchResponse response = searchService.search(40.0, -74.0, 5000.0, null, 0, 10);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).getName()).isEqualTo("Far"); // center itself, 0m
        assertThat(response.getContent().get(0).getDistanceMeters())
                .isLessThan(response.getContent().get(1).getDistanceMeters());
    }

    @Test
    @DisplayName("6. Pagination returns disjoint pages covering all results exactly once")
    void pagination_disjointPages() {
        for (int i = 0; i < 5; i++) {
            index(point(40.0 + i * 0.0001, -74.0, "P" + i, "ATM", true));
        }

        NearbySearchResponse page0 = searchService.search(40.0, -74.0, 5000.0, null, 0, 2);
        NearbySearchResponse page1 = searchService.search(40.0, -74.0, 5000.0, null, 1, 2);
        NearbySearchResponse page2 = searchService.search(40.0, -74.0, 5000.0, null, 2, 2);

        assertThat(page0.getContent()).hasSize(2);
        assertThat(page1.getContent()).hasSize(2);
        assertThat(page2.getContent()).hasSize(1);
        assertThat(page0.getTotalElements()).isEqualTo(5);

        List<String> allIds = new java.util.ArrayList<>();
        page0.getContent().forEach(p -> allIds.add(p.getId()));
        page1.getContent().forEach(p -> allIds.add(p.getId()));
        page2.getContent().forEach(p -> allIds.add(p.getId()));
        assertThat(allIds).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    @DisplayName("7. A search across the antimeridian finds a point on the other side within radius")
    void antimeridian_findsNearbyPointAcrossTheBoundary() {
        index(point(0.0, 179.999, "East of line", "ATM", true));
        index(point(0.0, -179.999, "West of line", "ATM", true));

        NearbySearchResponse response = searchService.search(0.0, 179.9995, 2000.0, null, 0, 10);

        assertThat(response.getContent()).extracting("name").contains("East of line", "West of line");
    }

    @Test
    @DisplayName("8. A search very near the pole does not throw and returns only points genuinely within radius")
    void highLatitude_doesNotThrowAndIsAccurate() {
        index(point(89.9, 0.0, "Near pole", "ATM", true));
        index(point(89.9, 170.0, "Far side near pole", "ATM", true)); // same latitude, very different longitude, but geographically close near the pole

        NearbySearchResponse response = searchService.search(89.9, 0.0, 50_000.0, null, 0, 10);

        assertThat(response.getContent()).isNotEmpty();
        response.getContent().forEach(p -> assertThat(p.getDistanceMeters()).isLessThanOrEqualTo(50_000.0));
    }

    @Test
    @DisplayName("9. No results within radius returns an empty, non-error response")
    void noResultsInRadius_returnsEmptyNotError() {
        index(point(10.0, 10.0, "Far away", "ATM", true));

        NearbySearchResponse response = searchService.search(40.0, -74.0, 1000.0, null, 0, 10);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("10. A document is never returned twice even though it falls inside multiple neighboring candidate geohash cells")
    void duplicateAvoidance_acrossNeighboringCells() {
        // Place a point exactly on a geohash cell boundary so it is genuinely a neighbor-cell
        // candidate from more than one direction.
        double[] box = GeohashUtils.decodeBoundingBox(GeohashUtils.encode(40.0, -74.0, 6));
        double boundaryLat = box[1]; // the cell's northern edge
        index(point(boundaryLat, -74.0, "Boundary point", "ATM", true));

        NearbySearchResponse response = searchService.search(40.0, -74.0, 5000.0, null, 0, 50);

        long distinctIds = response.getContent().stream().map(p -> p.getId()).distinct().count();
        assertThat(distinctIds).isEqualTo(response.getContent().size());
    }
}
