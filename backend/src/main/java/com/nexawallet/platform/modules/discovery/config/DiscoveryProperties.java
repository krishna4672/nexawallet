package com.nexawallet.platform.modules.discovery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the nearby-service-point search endpoint (see {@code nexa.discovery.*}).
 */
@Component
@ConfigurationProperties(prefix = "nexa.discovery")
@Getter
@Setter
public class DiscoveryProperties {

    /** Upper bound on the radius a caller may request. Prevents a single query from scanning most of the index. */
    private double maxRadiusMeters = 50_000; // 50km

    /** Radius used when the caller omits one. */
    private double defaultRadiusMeters = 2_000; // 2km

    /** Upper bound on page size. */
    private int maxPageSize = 100;

    /** Page size used when the caller omits one. */
    private int defaultPageSize = 20;

    /** Elasticsearch index name for service points (also bound into {@code @Document(indexName=...)}). */
    private String servicePointsIndex = "service_points_v1";

    /** Batch size for the bounded reindex job (see {@code ServicePointReindexService}). */
    private int reindexBatchSize = 200;
}
