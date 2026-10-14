package com.nexawallet.platform.health.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * System health response payload returned by the health check controller.
 */
@Getter
@Builder
public class SystemHealthResponse {
    private final String status;
    private final String application;
    private final String version;
    private final String environment;
    private final OffsetDateTime timestamp;
    private final Map<String, String> components;
}
