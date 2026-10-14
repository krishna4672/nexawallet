package com.nexawallet.platform.modules.wallet.service;

import com.nexawallet.platform.common.exception.InsufficientBalanceException;
import com.nexawallet.platform.common.exception.ResourceNotFoundException;
import com.nexawallet.platform.common.exception.WalletFrozenException;
import com.nexawallet.platform.modules.ledger.entity.EntryDirection;
import com.nexawallet.platform.modules.ledger.entity.LedgerAccountEntity;
import com.nexawallet.platform.modules.ledger.entity.LedgerAccountType;
import com.nexawallet.platform.modules.ledger.repository.LedgerAccountRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerEntryRepository;
import com.nexawallet.platform.modules.transfer.entity.TransactionEntity;
import com.nexawallet.platform.modules.transfer.entity.TransactionStatus;
import com.nexawallet.platform.modules.transfer.entity.TransactionType;
import com.nexawallet.platform.modules.transfer.repository.TransactionRepository;
import com.nexawallet.platform.modules.wallet.dto.DepositRequest;
import com.nexawallet.platform.modules.wallet.dto.TransactionResponse;
import com.nexawallet.platform.modules.wallet.dto.WalletResponse;
import com.nexawallet.platform.modules.wallet.entity.WalletEntity;
import com.nexawallet.platform.modules.wallet.entity.WalletStatus;
import com.nexawallet.platform.modules.wallet.repository.WalletRepository;
import com.nexawallet.platform.modules.ledger.entity.LedgerEntryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * WalletServiceImpl handles balance queries, simulated deposits, and transaction history.
 *
 * Deposit flow (double-entry):
 *   DEBIT  System Settlement account  (money leaves system pool)
 *   CREDIT User Wallet ledger account (money arrives in user wallet)
 *   wallet.balance is updated atomically within the same DB transaction.
 */
@Service
public class WalletServiceImpl implements WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletServiceImpl.class);

    private final WalletRepository walletRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionRepository transactionRepository;

    public WalletServiceImpl(
            WalletRepository walletRepository,
            LedgerAccountRepository ledgerAccountRepository,
            LedgerEntryRepository ledgerEntryRepository,
            TransactionRepository transactionRepository
    ) {
        this.walletRepository = walletRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse getMyWallet(UUID userId) {
        WalletEntity wallet = findPrimaryWallet(userId);
        return toWalletResponse(wallet);
    }

    @Override
    @Transactional
    public TransactionResponse deposit(UUID userId, DepositRequest request) {
        // 1. Acquire pessimistic write-lock on wallet to prevent concurrent balance mutation
        WalletEntity wallet = findPrimaryWallet(userId);
        WalletEntity lockedWallet = walletRepository.findByIdForUpdate(wallet.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", wallet.getId()));

        if (lockedWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletFrozenException(lockedWallet.getWalletNumber());
        }

        BigDecimal amount = request.getAmount().setScale(4, java.math.RoundingMode.HALF_UP);

        // 2. Resolve the System Settlement ledger account (or create one lazily if not seeded)
        LedgerAccountEntity settlementAccount = ledgerAccountRepository
                .findByAccountTypeAndCurrency(LedgerAccountType.SYSTEM_SETTLEMENT, "USD")
                .orElseGet(() -> ledgerAccountRepository.save(
                        LedgerAccountEntity.builder()
                                .accountType(LedgerAccountType.SYSTEM_SETTLEMENT)
                                .currency("USD")
                                .build()
                ));

        // 3. Resolve the user's ledger account
        LedgerAccountEntity userLedgerAccount = ledgerAccountRepository
                .findByWalletId(lockedWallet.getId())
                .orElseThrow(() -> new ResourceNotFoundException("LedgerAccount for wallet", lockedWallet.getId()));

        // 4. Generate a unique reference code
        String referenceCode = "DEP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();

        // 5. Create and persist the PENDING transaction record
        TransactionEntity transaction = transactionRepository.save(
                TransactionEntity.builder()
                        .referenceCode(referenceCode)
                        .targetWallet(lockedWallet)
                        .amount(amount)
                        .currency("USD")
                        .type(TransactionType.DEPOSIT_SIMULATED)
                        .status(TransactionStatus.PENDING)
                        .description(request.getDescription() != null ? request.getDescription() : "Simulated deposit")
                        .build()
        );

        // 6. Write double-entry ledger postings (immutable, append-only)
        //    DEBIT  Settlement account  — money leaves the system liquidity pool
        ledgerEntryRepository.save(LedgerEntryEntity.builder()
                .transaction(transaction)
                .ledgerAccount(settlementAccount)
                .direction(EntryDirection.DEBIT)
                .amount(amount)
                .build());

        //    CREDIT User Wallet account — money arrives in customer's pocket
        ledgerEntryRepository.save(LedgerEntryEntity.builder()
                .transaction(transaction)
                .ledgerAccount(userLedgerAccount)
                .direction(EntryDirection.CREDIT)
                .amount(amount)
                .build());

        // 7. Update wallet balance atomically within this transaction
        lockedWallet.setBalance(lockedWallet.getBalance().add(amount));
        walletRepository.save(lockedWallet);

        // 8. Mark transaction SETTLED
        transaction.setStatus(TransactionStatus.SETTLED);
        TransactionEntity settled = transactionRepository.save(transaction);

        log.info("Simulated deposit of {} USD settled for wallet {}. New balance: {}",
                amount, lockedWallet.getWalletNumber(), lockedWallet.getBalance());

        return toTransactionResponse(settled);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactionHistory(UUID userId, Pageable pageable) {
        WalletEntity wallet = findPrimaryWallet(userId);
        return transactionRepository
                .findByWalletId(wallet.getId(), pageable)
                .map(this::toTransactionResponse);
    }

    // ──────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────

    private WalletEntity findPrimaryWallet(UUID userId) {
        List<WalletEntity> wallets = walletRepository.findByUserId(userId);
        if (wallets.isEmpty()) {
            throw new ResourceNotFoundException("Wallet", userId);
        }
        return wallets.get(0);
    }

    WalletResponse toWalletResponse(WalletEntity wallet) {
        return WalletResponse.builder()
                .id(wallet.getId())
                .walletNumber(wallet.getWalletNumber())
                .balance(wallet.getBalance())
                .currency(wallet.getCurrency())
                .status(wallet.getStatus().name())
                .createdAt(wallet.getCreatedAt())
                .updatedAt(wallet.getUpdatedAt())
                .build();
    }

    TransactionResponse toTransactionResponse(TransactionEntity tx) {
        return TransactionResponse.builder()
                .transactionId(tx.getId())
                .referenceCode(tx.getReferenceCode())
                .type(tx.getType().name())
                .status(tx.getStatus().name())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .sourceWalletNumber(tx.getSourceWallet() != null ? tx.getSourceWallet().getWalletNumber() : null)
                .targetWalletNumber(tx.getTargetWallet() != null ? tx.getTargetWallet().getWalletNumber() : null)
                .description(tx.getDescription())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
