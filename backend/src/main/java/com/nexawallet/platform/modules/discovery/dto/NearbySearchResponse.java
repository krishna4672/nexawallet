package com.nexawallet.platform.modules.discovery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Paginated nearby-search result envelope — deliberately simple rather than reusing Spring's {@code Page<>}, since this wraps an Elasticsearch hit list rather than a JPA page. */
@Getter
@Builder
@AllArgsConstructor
public class NearbySearchResponse {
    private List<NearbyServicePointResponse> content;
    private int page;
    private int size;
    private long totalElements;
}
