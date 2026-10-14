package com.nexawallet.platform.modules.discovery.config;

import com.nexawallet.platform.modules.discovery.document.ServicePointDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Ensures the service-points index exists with its explicit mapping on startup — idempotent
 * (checks {@code exists()} first, never drops or recreates an existing index) and tolerant of
 * Elasticsearch being unreachable at boot (logs and moves on, same tolerance as {@code KafkaAdmin}
 * elsewhere in this codebase; it does not fail application startup).
 *
 * This does NOT populate any documents — an empty freshly-created index still needs
 * {@code ServicePointReindexService} (or live Kafka-driven upserts) to actually contain anything.
 * See the README "Reindex & recovery" section for recreating the index after a mapping change.
 */
@Component
public class ServicePointIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(ServicePointIndexInitializer.class);

    private final ElasticsearchOperations elasticsearchOperations;

    public ServicePointIndexInitializer(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexExists() {
        try {
            IndexOperations indexOps = elasticsearchOperations.indexOps(ServicePointDocument.class);
            if (indexOps.exists()) {
                log.info("Elasticsearch index '{}' already exists", indexOps.getIndexCoordinates().getIndexName());
                return;
            }
            indexOps.create();
            indexOps.putMapping(indexOps.createMapping(ServicePointDocument.class));
            log.info("Created Elasticsearch index '{}' with the service-point mapping", indexOps.getIndexCoordinates().getIndexName());
        } catch (Exception elasticsearchUnavailable) {
            log.warn("Could not verify/create the service-points Elasticsearch index at startup ({}); "
                            + "nearby search will return 503 until Elasticsearch is reachable and the index exists",
                    elasticsearchUnavailable.getClass().getSimpleName());
        }
    }
}
