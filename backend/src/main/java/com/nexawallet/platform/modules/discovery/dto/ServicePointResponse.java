package com.nexawallet.platform.modules.discovery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Admin-facing representation of a service point (PostgreSQL row), returned by the write endpoints. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePointResponse {
    private UUID id;
    private String name;
    private String type;
    private double latitude;
    private double longitude;
    private String geohash;
    private String address;
    private boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
