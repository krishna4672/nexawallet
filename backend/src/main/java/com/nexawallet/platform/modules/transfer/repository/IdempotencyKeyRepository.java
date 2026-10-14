package com.nexawallet.platform.modules.transfer.repository;

import com.nexawallet.platform.modules.transfer.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for IdempotencyKeyEntity.
 */
@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, String> {

    Optional<IdempotencyKeyEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<IdempotencyKeyEntity> findByIdempotencyKeyAndUserId(String idempotencyKey, UUID userId);

    void deleteByExpiresAtBefore(OffsetDateTime threshold);
}
