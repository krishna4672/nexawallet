package com.nexawallet.platform.modules.discovery.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Admin-only partial update. Every field is optional — only non-null fields are applied, matching
 * a PATCH-style contract. {@code active=false} is how a service point is deactivated (soft-removed
 * from search results, never hard-deleted — keeps the audit trail and lets it be reactivated).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePointUpdateRequest {

    @Size(max = 150)
    private String name;

    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    private Double latitude;

    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    private Double longitude;

    @Size(max = 255)
    private String address;

    private Boolean active;
}
