package com.nexawallet.platform.modules.outbox.controller;

import com.nexawallet.platform.modules.outbox.dto.OutboxStatsResponse;
import com.nexawallet.platform.modules.outbox.service.OutboxMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative visibility into the transactional outbox / Kafka pipeline.
 * Restricted to {@code ROLE_ADMIN} — never exposes raw event payloads, only
 * aggregate counts and ages, and is not reachable by ordinary customers.
 */
@RestController
@RequestMapping("/api/v1/admin/outbox")
@Tag(name = "Admin — Outbox", description = "Operational visibility into the transactional outbox and Kafka pipeline (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearerAuth")
public class OutboxAdminController {

    private final OutboxMetricsService outboxMetricsService;

    public OutboxAdminController(OutboxMetricsService outboxMetricsService) {
        this.outboxMetricsService = outboxMetricsService;
    }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Outbox pipeline health snapshot",
            description = "Pending/publishing/published/failed counts, oldest pending event age, and retry counts. ROLE_ADMIN only.")
    public ResponseEntity<OutboxStatsResponse> getStats() {
        return ResponseEntity.ok(outboxMetricsService.snapshot());
    }
}
