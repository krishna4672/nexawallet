package com.nexawallet.platform.modules.ledger.repository;

import com.nexawallet.platform.modules.ledger.entity.LedgerAccountEntity;
import com.nexawallet.platform.modules.ledger.entity.LedgerAccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for LedgerAccountEntity.
 */
@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccountEntity, UUID> {

    Optional<LedgerAccountEntity> findByWalletId(UUID walletId);

    Optional<LedgerAccountEntity> findByAccountTypeAndCurrency(LedgerAccountType accountType, String currency);
}
