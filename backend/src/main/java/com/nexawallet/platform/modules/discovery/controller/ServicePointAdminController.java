package com.nexawallet.platform.modules.discovery.controller;

import com.nexawallet.platform.modules.discovery.dto.ServicePointCreateRequest;
import com.nexawallet.platform.modules.discovery.dto.ServicePointResponse;
import com.nexawallet.platform.modules.discovery.dto.ServicePointUpdateRequest;
import com.nexawallet.platform.modules.discovery.service.ServicePointAdminService;
import com.nexawallet.platform.modules.discovery.service.ServicePointReindexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Administrative write operations for service points. Restricted to {@code ROLE_ADMIN} — there is
 * no public endpoint that lets an ordinary customer add, edit, or remove a discoverable location.
 */
@RestController
@RequestMapping("/api/v1/admin/service-points")
@Tag(name = "Admin — Service Points", description = "Create/update/deactivate discoverable locations and manage the search index (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class ServicePointAdminController {

    private final ServicePointAdminService servicePointAdminService;
    private final ServicePointReindexService reindexService;

    public ServicePointAdminController(ServicePointAdminService servicePointAdminService, ServicePointReindexService reindexService) {
        this.servicePointAdminService = servicePointAdminService;
        this.reindexService = reindexService;
    }

    @PostMapping
    @Operation(summary = "Create a service point", description = "Persists the location in PostgreSQL and records an outbox event that indexes it into Elasticsearch asynchronously.")
    public ResponseEntity<ServicePointResponse> create(@Valid @RequestBody ServicePointCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicePointAdminService.create(request));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update or deactivate a service point", description = "Partial update; set active=false to deactivate. Records an outbox event with the full new snapshot.")
    public ResponseEntity<ServicePointResponse> update(@PathVariable UUID id, @Valid @RequestBody ServicePointUpdateRequest request) {
        return ResponseEntity.ok(servicePointAdminService.update(id, request));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a service point", description = "Soft-removes it from search results without deleting the PostgreSQL row.")
    public ResponseEntity<ServicePointResponse> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(servicePointAdminService.deactivate(id));
    }

    @PostMapping("/reindex")
    @Operation(summary = "Rebuild the search index from PostgreSQL",
            description = "Bounded, paginated batch job that re-reads every service point from PostgreSQL and upserts it into Elasticsearch. "
                    + "Safe to run repeatedly — every write is an upsert keyed by the service point's own id, so re-running never creates duplicate documents. "
                    + "See the README \"Reindex & recovery\" section for when to use this.")
    public ResponseEntity<Map<String, Object>> reindex() {
        long indexed = reindexService.reindexAll();
        return ResponseEntity.ok(Map.of("status", "COMPLETED", "documentsIndexed", indexed));
    }

    @PostMapping("/index/recreate")
    @Operation(summary = "Delete and recreate the search index with the current mapping",
            description = "Use after a mapping change. The index is left EMPTY afterwards — call /reindex immediately after this to repopulate it from PostgreSQL.")
    public ResponseEntity<Map<String, String>> recreateIndex() {
        reindexService.recreateIndex();
        return ResponseEntity.ok(Map.of("status", "INDEX_RECREATED_EMPTY"));
    }
}
