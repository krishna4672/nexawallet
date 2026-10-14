package com.nexawallet.platform.modules.outbox.service;

import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Claims a bounded batch of PENDING outbox rows for this publisher instance.
 *
 * The claim is a single short transaction: lock eligible rows with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} (so a concurrent publisher instance
 * silently skips whatever this one has already locked), flip them to PUBLISHING,
 * then commit. The lock is held only for these two statements — never while a
 * network round-trip to Kafka is in flight, which happens strictly afterwards.
 */
@Service
public class OutboxClaimService {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxClaimService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEventEntity> claimBatch(int batchSize) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> ids = outboxEventRepository.selectClaimableIdsForUpdateSkipLocked(now, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        outboxEventRepository.markPublishing(ids);
        return outboxEventRepository.findAllById(ids);
    }
}
