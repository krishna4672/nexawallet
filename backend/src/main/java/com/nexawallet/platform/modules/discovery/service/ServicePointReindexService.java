package com.nexawallet.platform.modules.discovery.service;

import com.nexawallet.platform.modules.discovery.config.DiscoveryProperties;
import com.nexawallet.platform.modules.discovery.document.ServicePointDocument;
import com.nexawallet.platform.modules.discovery.entity.ServicePointEntity;
import com.nexawallet.platform.modules.discovery.exception.SearchUnavailableException;
import com.nexawallet.platform.modules.discovery.repository.ServicePointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Rebuilds the Elasticsearch service-points index from PostgreSQL — the documented recovery path
 * after an index loss or a mapping change.
 *
 * <h2>Why this is paginated, not one unbounded scan</h2>
 * The service_points table is read in bounded pages ({@code nexa.discovery.reindex-batch-size},
 * default 200), each converted and upserted as one Elasticsearch bulk write, rather than loading
 * the whole table into memory or issuing one write per document. This keeps memory use and request
 * size bounded regardless of how large the table grows.
 *
 * <h2>Repeatability</h2>
 * Every write is an upsert keyed by the service point's own UUID (the Elasticsearch {@code _id}),
 * so running this job twice in a row — or concurrently with itself — never creates a duplicate
 * logical document; the second run simply overwrites the same documents with the same (or newer)
 * data.
 *
 * <h2>Events arriving mid-reindex (documented limitation)</h2>
 * This job reads a snapshot of PostgreSQL at read time. If a Kafka-driven upsert for some service
 * point lands in Elasticsearch <i>after</i> this job has already read (but not yet written) that
 * same row, the job's write can overwrite the newer Kafka-driven write with an older snapshot —
 * last-write-wins at the Elasticsearch document level, decided purely by write order, not by any
 * vector-clock or version check. In practice this self-heals as soon as the next event for that
 * service point is processed (or the next reindex run), but it is not a guaranteed-ordering,
 * exactly-consistent rebuild — this is deliberately not oversold as one.
 */
@Service
public class ServicePointReindexService {

    private static final Logger log = LoggerFactory.getLogger(ServicePointReindexService.class);

    private final ServicePointRepository servicePointRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final DiscoveryProperties properties;

    public ServicePointReindexService(
            ServicePointRepository servicePointRepository,
            ElasticsearchOperations elasticsearchOperations,
            DiscoveryProperties properties
    ) {
        this.servicePointRepository = servicePointRepository;
        this.elasticsearchOperations = elasticsearchOperations;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public long reindexAll() {
        try {
            int page = 0;
            long total = 0;
            List<ServicePointEntity> batch;
            do {
                batch = servicePointRepository
                        .findAll(PageRequest.of(page, properties.getReindexBatchSize(), Sort.by("createdAt")))
                        .getContent();
                if (!batch.isEmpty()) {
                    List<ServicePointDocument> documents = batch.stream().map(this::toDocument).toList();
                    elasticsearchOperations.save(documents);
                    total += documents.size();
                    log.info("Reindexed batch {} ({} documents, {} total so far)", page, documents.size(), total);
                }
                page++;
            } while (!batch.isEmpty());
            log.info("Service-point reindex complete: {} document(s) upserted", total);
            return total;
        } catch (SearchUnavailableException propagate) {
            throw propagate;
        } catch (Exception elasticsearchUnavailable) {
            throw new SearchUnavailableException("Reindex failed: Elasticsearch unavailable", elasticsearchUnavailable);
        }
    }

    /** Deletes (if present) and recreates the index with the current mapping — leaves it EMPTY; call {@link #reindexAll()} afterwards. */
    public void recreateIndex() {
        try {
            var indexOps = elasticsearchOperations.indexOps(ServicePointDocument.class);
            if (indexOps.exists()) {
                indexOps.delete();
            }
            indexOps.create();
            indexOps.putMapping(indexOps.createMapping(ServicePointDocument.class));
            log.info("Recreated Elasticsearch index '{}' (now empty)", indexOps.getIndexCoordinates().getIndexName());
        } catch (Exception elasticsearchUnavailable) {
            throw new SearchUnavailableException("Index recreation failed: Elasticsearch unavailable", elasticsearchUnavailable);
        }
    }

    private ServicePointDocument toDocument(ServicePointEntity entity) {
        return ServicePointDocument.builder()
                .id(entity.getId().toString())
                .name(entity.getName())
                .type(entity.getType().name())
                .active(entity.isActive())
                .location(new GeoPoint(entity.getLatitude(), entity.getLongitude()))
                .geohash(entity.getGeohash())
                .address(entity.getAddress())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
