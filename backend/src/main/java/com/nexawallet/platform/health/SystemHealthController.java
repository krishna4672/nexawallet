package com.nexawallet.platform.health;

import com.nexawallet.platform.health.dto.SystemHealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller providing system health and platform metadata.
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "System Health", description = "Endpoints for platform status and health monitoring")
public class SystemHealthController {

    @Value("${spring.application.name:nexawallet-backend}")
    private String applicationName;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @GetMapping
    @Operation(summary = "Check platform health status", description = "Returns system health, active profile, and component status")
    public ResponseEntity<SystemHealthResponse> getHealth() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("coreEngine", "UP");
        components.put("databaseTarget", "PostgreSQL-16");
        components.put("ledgerStatus", "DOUBLE_ENTRY_APPEND_ONLY");
        components.put("phase", "PHASE_1_FOUNDATION");

        SystemHealthResponse response = SystemHealthResponse.builder()
                .status("UP")
                .application(applicationName)
                .version("1.0.0-SNAPSHOT")
                .environment(activeProfile)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .components(components)
                .build();

        return ResponseEntity.ok(response);
    }
}
