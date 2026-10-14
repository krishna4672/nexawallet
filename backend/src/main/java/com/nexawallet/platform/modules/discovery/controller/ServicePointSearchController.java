package com.nexawallet.platform.modules.discovery.controller;

import com.nexawallet.platform.modules.discovery.dto.NearbySearchResponse;
import com.nexawallet.platform.modules.discovery.service.ServicePointSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only location discovery — public, like any "find a nearby ATM" feature; no JWT required.
 * Administrative write/reindex operations live under {@code /api/v1/admin/service-points} and are
 * ROLE_ADMIN-protected (see {@link ServicePointAdminController}).
 */
@RestController
@RequestMapping("/api/v1/service-points")
@Tag(name = "Service Points", description = "Public nearby service-point discovery (ATMs, branches, partner kiosks, merchants)")
public class ServicePointSearchController {

    private final ServicePointSearchService searchService;

    public ServicePointSearchController(ServicePointSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/nearby")
    @Operation(summary = "Find nearby service points",
            description = "Geo-distance search against the Elasticsearch discovery index. "
                    + "radiusMeters is bounded by nexa.discovery.max-radius-meters; size is bounded by nexa.discovery.max-page-size. "
                    + "Returns 400 for invalid parameters and 503 if the search backend is unavailable.")
    public ResponseEntity<NearbySearchResponse> nearby(
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false) Double radiusMeters,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(searchService.search(lat, lon, radiusMeters, category, page, size));
    }
}
