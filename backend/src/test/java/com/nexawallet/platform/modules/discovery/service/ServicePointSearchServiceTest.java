package com.nexawallet.platform.modules.discovery.service;

import com.nexawallet.platform.modules.discovery.config.DiscoveryProperties;
import com.nexawallet.platform.modules.discovery.document.ServicePointDocument;
import com.nexawallet.platform.modules.discovery.exception.SearchUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.data.elasticsearch.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServicePointSearchService — Unit Tests")
class ServicePointSearchServiceTest {

    @Mock private ElasticsearchOperations elasticsearchOperations;

    private DiscoveryProperties properties;
    private ServicePointSearchService service;

    @BeforeEach
    void setUp() {
        properties = new DiscoveryProperties();
        service = new ServicePointSearchService(elasticsearchOperations, properties);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Latitude outside [-90,90] is rejected")
    void search_invalidLatitude_throws() {
        assertThatThrownBy(() -> service.search(91.0, 0.0, 1000.0, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2. Longitude outside [-180,180] is rejected")
    void search_invalidLongitude_throws() {
        assertThatThrownBy(() -> service.search(0.0, 181.0, 1000.0, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("3. A radius beyond the configured maximum is rejected")
    void search_radiusAboveMax_throws() {
        assertThatThrownBy(() -> service.search(0.0, 0.0, properties.getMaxRadiusMeters() + 1, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4. A zero or negative radius is rejected")
    void search_nonPositiveRadius_throws() {
        assertThatThrownBy(() -> service.search(0.0, 0.0, 0.0, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(0.0, 0.0, -5.0, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("5. A page size beyond the configured maximum is rejected")
    void search_sizeAboveMax_throws() {
        assertThatThrownBy(() -> service.search(0.0, 0.0, 1000.0, null, 0, properties.getMaxPageSize() + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("6. A negative page index is rejected")
    void search_negativePage_throws() {
        assertThatThrownBy(() -> service.search(0.0, 0.0, 1000.0, null, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("7. An unrecognized category is rejected with a helpful message")
    void search_invalidCategory_throws() {
        assertThatThrownBy(() -> service.search(0.0, 0.0, 1000.0, "SPACESHIP", 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ATM");
    }

    @Test
    @DisplayName("8. Defaults are applied when radius/page/size are omitted")
    void search_omittedParameters_useConfiguredDefaults() {
        mockEmptyResult();
        var response = service.search(0.0, 0.0, null, null, null, null);
        assertThat(response.getPage()).isEqualTo(0);
        assertThat(response.getSize()).isEqualTo(properties.getDefaultPageSize());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Backend-unavailable handling
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("9. An Elasticsearch failure is surfaced as SearchUnavailableException, not propagated raw")
    void search_elasticsearchThrows_mapsToSearchUnavailable() {
        when(elasticsearchOperations.search(any(Query.class), any(Class.class))).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.search(0.0, 0.0, 1000.0, null, 0, 10))
                .isInstanceOf(SearchUnavailableException.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Successful mapping
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10. A successful search maps hits to the response DTO, including distance from the sort value")
    void search_success_mapsHitsAndDistance() {
        ServicePointDocument doc = ServicePointDocument.builder()
                .id("sp-1").name("Corner ATM").type("ATM").active(true)
                .location(new GeoPoint(40.01, -73.0)).address("1 Main St").build();

        @SuppressWarnings("unchecked")
        SearchHit<ServicePointDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        when(hit.getSortValues()).thenReturn(List.of(123.45));

        @SuppressWarnings("unchecked")
        SearchHits<ServicePointDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(hits.getTotalHits()).thenReturn(1L);

        when(elasticsearchOperations.search(any(Query.class), eq(ServicePointDocument.class))).thenReturn(hits);

        var response = service.search(40.0, -73.0, 5000.0, "ATM", 0, 10);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getId()).isEqualTo("sp-1");
        assertThat(response.getContent().get(0).getDistanceMeters()).isEqualTo(123.45);
        assertThat(response.getTotalElements()).isEqualTo(1L);
    }

    private void mockEmptyResult() {
        @SuppressWarnings("unchecked")
        SearchHits<ServicePointDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of());
        when(hits.getTotalHits()).thenReturn(0L);
        when(elasticsearchOperations.search(any(Query.class), any(Class.class))).thenReturn(hits);
    }
}
