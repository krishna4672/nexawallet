package com.nexawallet.platform.modules.discovery.service;

import com.nexawallet.platform.common.exception.ResourceNotFoundException;
import com.nexawallet.platform.common.geo.GeohashUtils;
import com.nexawallet.platform.modules.discovery.dto.ServicePointCreateRequest;
import com.nexawallet.platform.modules.discovery.dto.ServicePointResponse;
import com.nexawallet.platform.modules.discovery.dto.ServicePointUpdateRequest;
import com.nexawallet.platform.modules.discovery.entity.ServicePointEntity;
import com.nexawallet.platform.modules.discovery.repository.ServicePointRepository;
import com.nexawallet.platform.modules.outbox.service.OutboxEventWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Administrative (ROLE_ADMIN-only, enforced at the controller) create/update/deactivate operations
 * for service points. Every mutation writes the resulting full snapshot to the transactional
 * outbox, in the very same database transaction as the PostgreSQL row change — exactly the pattern
 * {@code TransferServiceImpl}/{@code WalletServiceImpl} already use for financial events (Phase 4).
 * If the transaction rolls back, the outbox row rolls back with it; nothing here ever talks to
 * Kafka or Elasticsearch directly.
 *
 * Geohash precision 9 (~5m) is stored for reference/candidate-selection use (see {@code GeohashUtils}).
 */
@Service
public class ServicePointAdminService {

    private static final Logger log = LoggerFactory.getLogger(ServicePointAdminService.class);
    private static final int STORED_GEOHASH_PRECISION = 9;
    private static final String AGGREGATE_TYPE = "ServicePoint";

    private final ServicePointRepository servicePointRepository;
    private final OutboxEventWriter outboxEventWriter;

    public ServicePointAdminService(ServicePointRepository servicePointRepository, OutboxEventWriter outboxEventWriter) {
        this.servicePointRepository = servicePointRepository;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional
    public ServicePointResponse create(ServicePointCreateRequest request) {
        String geohash = GeohashUtils.encode(request.getLatitude(), request.getLongitude(), STORED_GEOHASH_PRECISION);

        ServicePointEntity entity = ServicePointEntity.builder()
                .name(request.getName())
                .type(request.getType())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .geohash(geohash)
                .address(request.getAddress())
                .active(true)
                .build();

        ServicePointEntity saved = servicePointRepository.save(entity);
        recordOutboxEvent(saved, "SERVICE_POINT_CREATED");
        log.info("Created service point {} ({}) at geohash {}", saved.getId(), saved.getName(), saved.getGeohash());
        return toResponse(saved);
    }

    @Transactional
    public ServicePointResponse update(UUID id, ServicePointUpdateRequest request) {
        ServicePointEntity entity = servicePointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ServicePoint", id));

        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        boolean coordinatesChanged = request.getLatitude() != null || request.getLongitude() != null;
        if (request.getLatitude() != null) {
            entity.setLatitude(request.getLatitude());
        }
        if (request.getLongitude() != null) {
            entity.setLongitude(request.getLongitude());
        }
        if (coordinatesChanged) {
            entity.setGeohash(GeohashUtils.encode(entity.getLatitude(), entity.getLongitude(), STORED_GEOHASH_PRECISION));
        }
        if (request.getAddress() != null) {
            entity.setAddress(request.getAddress());
        }
        if (request.getActive() != null) {
            entity.setActive(request.getActive());
        }

        ServicePointEntity saved = servicePointRepository.save(entity);
        recordOutboxEvent(saved, saved.isActive() ? "SERVICE_POINT_UPDATED" : "SERVICE_POINT_DEACTIVATED");
        log.info("Updated service point {} ({})", saved.getId(), saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public ServicePointResponse deactivate(UUID id) {
        ServicePointEntity entity = servicePointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ServicePoint", id));
        entity.setActive(false);
        ServicePointEntity saved = servicePointRepository.save(entity);
        recordOutboxEvent(saved, "SERVICE_POINT_DEACTIVATED");
        log.info("Deactivated service point {} ({})", saved.getId(), saved.getName());
        return toResponse(saved);
    }

    /**
     * Every event type (CREATED/UPDATED/DEACTIVATED) carries the full current snapshot — the
     * consumer always upserts the same shape regardless of which event type it reads, which keeps
     * it trivially idempotent and tolerant of this snapshot being (re)applied more than once.
     */
    private void recordOutboxEvent(ServicePointEntity entity, String eventType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("servicePointId", entity.getId());
        data.put("name", entity.getName());
        data.put("type", entity.getType().name());
        data.put("latitude", entity.getLatitude());
        data.put("longitude", entity.getLongitude());
        data.put("geohash", entity.getGeohash());
        data.put("address", entity.getAddress());
        data.put("active", entity.isActive());
        data.put("updatedAt", entity.getUpdatedAt());
        outboxEventWriter.record(AGGREGATE_TYPE, entity.getId(), eventType, data);
    }

    private ServicePointResponse toResponse(ServicePointEntity entity) {
        return ServicePointResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .type(entity.getType().name())
                .latitude(entity.getLatitude())
                .longitude(entity.getLongitude())
                .geohash(entity.getGeohash())
                .address(entity.getAddress())
                .active(entity.isActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
