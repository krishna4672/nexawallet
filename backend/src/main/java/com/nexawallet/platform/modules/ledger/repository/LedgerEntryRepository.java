package com.nexawallet.platform.modules.ledger.repository;

import com.nexawallet.platform.modules.ledger.entity.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for LedgerEntryEntity.
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    List<LedgerEntryEntity> findByTransactionId(UUID transactionId);

    List<LedgerEntryEntity> findByLedgerAccountId(UUID ledgerAccountId);
}
