package com.nexawallet.platform.modules.outbox.repository;

import com.nexawallet.platform.modules.outbox.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Spring Data JPA Repository for the consumer-side idempotency registry.
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
