package com.nexawallet.platform.modules.wallet.repository;

import com.nexawallet.platform.modules.wallet.entity.WalletEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for WalletEntity with pessimistic row-locking support.
 */
@Repository
public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {

    List<WalletEntity> findByUserId(UUID userId);

    Optional<WalletEntity> findByWalletNumber(String walletNumber);

    boolean existsByWalletNumber(String walletNumber);

    /**
     * Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on the wallet by primary key.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.id = :id")
    Optional<WalletEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on the wallet by unique wallet number.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.walletNumber = :walletNumber")
    Optional<WalletEntity> findByWalletNumberForUpdate(@Param("walletNumber") String walletNumber);
}
