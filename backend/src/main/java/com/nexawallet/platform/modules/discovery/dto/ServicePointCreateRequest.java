package com.nexawallet.platform.modules.discovery.dto;

import com.nexawallet.platform.modules.discovery.entity.ServicePointType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Admin-only request to create a new discoverable service point (ATM, branch, partner kiosk, or
 * merchant). These are operator-curated entries, not user-submitted locations.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePointCreateRequest {

    @NotBlank
    @Size(max = 150)
    private String name;

    @NotNull
    private ServicePointType type;

    @NotNull
    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    private Double latitude;

    @NotNull
    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    private Double longitude;

    @Size(max = 255)
    private String address;
}
