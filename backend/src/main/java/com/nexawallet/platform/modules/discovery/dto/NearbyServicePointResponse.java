package com.nexawallet.platform.modules.discovery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Public-facing result row for the nearby-search endpoint. Only fields safe for any caller to see
 * — no internal ids beyond the stable service-point id, no timestamps, no audit metadata.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NearbyServicePointResponse {
    private String id;
    private String name;
    private String type;
    private double latitude;
    private double longitude;
    private String address;
    private double distanceMeters;
}
