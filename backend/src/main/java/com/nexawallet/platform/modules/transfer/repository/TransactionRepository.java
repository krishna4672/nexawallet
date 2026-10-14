package com.nexawallet.platform.modules.transfer.repository;

import com.nexawallet.platform.modules.transfer.entity.TransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for TransactionEntity.
 */
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    Optional<TransactionEntity> findByReferenceCode(String referenceCode);

    boolean existsByReferenceCode(String referenceCode);

    /**
     * Retrieves paginated transactions involving the specified wallet as either sender or recipient.
     */
    @Query("SELECT t FROM TransactionEntity t WHERE t.sourceWallet.id = :walletId OR t.targetWallet.id = :walletId ORDER BY t.createdAt DESC")
    Page<TransactionEntity> findByWalletId(@Param("walletId") UUID walletId, Pageable pageable);
}
