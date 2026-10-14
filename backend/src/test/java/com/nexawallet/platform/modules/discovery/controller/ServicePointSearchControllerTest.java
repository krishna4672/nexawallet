package com.nexawallet.platform.modules.discovery.controller;

import com.nexawallet.platform.modules.discovery.dto.NearbySearchResponse;
import com.nexawallet.platform.modules.discovery.dto.NearbyServicePointResponse;
import com.nexawallet.platform.modules.discovery.exception.SearchUnavailableException;
import com.nexawallet.platform.modules.discovery.service.ServicePointSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for the public nearby-search endpoint. The service layer is mocked here
 * so these run without a live Elasticsearch — real geo-query correctness is covered by
 * {@code ServicePointSearchServiceTest} (mocked ES client) and the Testcontainers-backed
 * {@code ServicePointNearbySearchIntegrationTest} (real ES).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Service Point Search Controller — Integration Tests")
class ServicePointSearchControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ServicePointSearchService searchService;

    @Test
    @DisplayName("1. The nearby endpoint is reachable WITHOUT any Authorization header (public, per design)")
    void nearby_noAuthHeader_isReachable() throws Exception {
        when(searchService.search(any(), any(), any(), any(), any(), any()))
                .thenReturn(NearbySearchResponse.builder().content(List.of()).page(0).size(20).totalElements(0).build());

        mockMvc.perform(get("/api/v1/service-points/nearby").param("lat", "40.0").param("lon", "-73.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    @DisplayName("2. A validation failure in the service (e.g. bad radius) maps to 400, in the standard error format")
    void nearby_serviceValidationFailure_returns400() throws Exception {
        when(searchService.search(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("radiusMeters must not exceed 50000"));

        mockMvc.perform(get("/api/v1/service-points/nearby").param("lat", "40.0").param("lon", "-73.0").param("radiusMeters", "999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("radiusMeters must not exceed 50000")));
    }

    @Test
    @DisplayName("3. Elasticsearch being unavailable maps to 503 — never a stack trace or ES internals")
    void nearby_searchUnavailable_returns503() throws Exception {
        when(searchService.search(any(), any(), any(), any(), any(), any()))
                .thenThrow(new SearchUnavailableException("boom", new RuntimeException("connection refused to 10.0.0.5:9200")));

        mockMvc.perform(get("/api/v1/service-points/nearby").param("lat", "40.0").param("lon", "-73.0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)))
                .andExpect(jsonPath("$.message", is("Search is temporarily unavailable. Please retry shortly.")))
                .andExpect(content -> {
                    String body = content.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("10.0.0.5").doesNotContain("RuntimeException");
                });
    }

    @Test
    @DisplayName("4. A successful search returns the mapped result list")
    void nearby_success_returnsResults() throws Exception {
        NearbyServicePointResponse point = NearbyServicePointResponse.builder()
                .id("sp-1").name("Corner ATM").type("ATM").latitude(40.01).longitude(-73.0).distanceMeters(111.0).build();
        when(searchService.search(eq(40.0), eq(-73.0), any(), any(), any(), any()))
                .thenReturn(NearbySearchResponse.builder().content(List.of(point)).page(0).size(20).totalElements(1).build());

        mockMvc.perform(get("/api/v1/service-points/nearby").param("lat", "40.0").param("lon", "-73.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id", is("sp-1")))
                .andExpect(jsonPath("$.content[0].distanceMeters", is(111.0)));
    }
}
