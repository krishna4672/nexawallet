package com.nexawallet.platform.modules.outbox.repository;

import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for OutboxEventEntity.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);

    @Query("SELECT MIN(e.createdAt) FROM OutboxEventEntity e WHERE e.status = :status")
    Optional<OffsetDateTime> findOldestCreatedAtByStatus(@Param("status") OutboxStatus status);

    @Query("SELECT MAX(e.retryCount) FROM OutboxEventEntity e WHERE e.status IN :statuses")
    Optional<Integer> findMaxRetryCountByStatusIn(@Param("statuses") List<OutboxStatus> statuses);

    /**
     * Step 1 of the claim: identifies up to {@code limit} eligible PENDING rows and takes a
     * row-level write lock on them, skipping any row already locked by a concurrent claimer
     * (PostgreSQL {@code FOR UPDATE SKIP LOCKED}). Must be called inside the same short
     * transaction as {@link #markPublishing(List)} so the lock is only ever held for the
     * duration of these two statements — never while waiting on Kafka.
     */
    @Query(value = """
            SELECT id FROM outbox_events
            WHERE status = 'PENDING'
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> selectClaimableIdsForUpdateSkipLocked(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /**
     * Step 2 of the claim: flips the just-locked rows to PUBLISHING so no other publisher
     * instance (or the next poll cycle of this one) will select them again as PENDING.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEventEntity e SET e.status = com.nexawallet.platform.modules.outbox.entity.OutboxStatus.PUBLISHING WHERE e.id IN :ids")
    int markPublishing(@Param("ids") List<UUID> ids);
}
