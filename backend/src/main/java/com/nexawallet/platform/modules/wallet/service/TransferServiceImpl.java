package com.nexawallet.platform.modules.wallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexawallet.platform.common.exception.DuplicateTransferException;
import com.nexawallet.platform.common.exception.InsufficientBalanceException;
import com.nexawallet.platform.common.exception.ResourceNotFoundException;
import com.nexawallet.platform.common.exception.WalletFrozenException;
import com.nexawallet.platform.modules.auth.entity.UserEntity;
import com.nexawallet.platform.modules.auth.repository.UserRepository;
import com.nexawallet.platform.modules.ledger.entity.EntryDirection;
import com.nexawallet.platform.modules.ledger.entity.LedgerAccountEntity;
import com.nexawallet.platform.modules.ledger.entity.LedgerEntryEntity;
import com.nexawallet.platform.modules.ledger.repository.LedgerAccountRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerEntryRepository;
import com.nexawallet.platform.modules.outbox.service.OutboxEventWriter;
import com.nexawallet.platform.modules.transfer.entity.IdempotencyKeyEntity;
import com.nexawallet.platform.modules.transfer.entity.IdempotencyStatus;
import com.nexawallet.platform.modules.transfer.entity.TransactionEntity;
import com.nexawallet.platform.modules.transfer.entity.TransactionStatus;
import com.nexawallet.platform.modules.transfer.entity.TransactionType;
import com.nexawallet.platform.modules.transfer.repository.IdempotencyKeyRepository;
import com.nexawallet.platform.modules.transfer.repository.TransactionRepository;
import com.nexawallet.platform.modules.wallet.dto.TransactionResponse;
import com.nexawallet.platform.modules.wallet.dto.TransferRequest;
import com.nexawallet.platform.modules.wallet.entity.WalletEntity;
import com.nexawallet.platform.modules.wallet.entity.WalletStatus;
import com.nexawallet.platform.modules.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TransferServiceImpl — Core fintech engine for NexaWallet.
 *
 * <h2>Transfer Algorithm (Double-Entry, Idempotent, Pessimistic Locking)</h2>
 * <ol>
 *   <li><b>Idempotency check</b> — look up existing key; if COMPLETED, return cached payload.</li>
 *   <li><b>Reserve idempotency slot</b> — insert PROCESSING record to block concurrent retries.</li>
 *   <li><b>Lock wallets in canonical order</b> — always lock lower UUID first to prevent deadlocks.</li>
 *   <li><b>Validate wallets</b> — both ACTIVE, no self-transfer, sufficient source balance.</li>
 *   <li><b>Write transaction record</b> — status PENDING.</li>
 *   <li><b>Write double-entry ledger postings</b> — DEBIT source, CREDIT target (immutable).</li>
 *   <li><b>Update wallet balances</b> — within the same DB transaction.</li>
 *   <li><b>Settle transaction</b> — mark SETTLED.</li>
 *   <li><b>Complete idempotency key</b> — store serialized response, mark COMPLETED.</li>
 * </ol>
 *
 * Isolation level READ_COMMITTED + pessimistic row-locking ensure serialisability
 * for concurrent transfers involving the same wallets.
 */
@Service
public class TransferServiceImpl implements TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final WalletRepository walletRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final UserRepository userRepository;
    private final WalletServiceImpl walletServiceImpl;
    private final OutboxEventWriter outboxEventWriter;

    public TransferServiceImpl(
            WalletRepository walletRepository,
            LedgerAccountRepository ledgerAccountRepository,
            LedgerEntryRepository ledgerEntryRepository,
            TransactionRepository transactionRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            UserRepository userRepository,
            WalletServiceImpl walletServiceImpl,
            OutboxEventWriter outboxEventWriter
    ) {
        this.walletRepository = walletRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.userRepository = userRepository;
        this.walletServiceImpl = walletServiceImpl;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse transfer(UUID senderId, String idempotencyKey, TransferRequest request) {
        BigDecimal amount = request.getAmount().setScale(4, RoundingMode.HALF_UP);

        // ── Step 1: Idempotency check ──────────────────────────────────────
        idempotencyKeyRepository.findByIdempotencyKeyAndUserId(idempotencyKey, senderId)
                .ifPresent(existing -> {
                    if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                        log.info("Idempotency hit for key '{}' — returning cached response", idempotencyKey);
                        throw new DuplicateTransferException(
                                "Transfer already completed",
                                existing.getResponsePayload()
                        );
                    }
                    if (existing.getStatus() == IdempotencyStatus.PROCESSING) {
                        throw new IllegalStateException(
                                "A concurrent transfer with idempotency key '" + idempotencyKey + "' is already in progress. Please retry in a moment.");
                    }
                });

        // ── Step 2: Reserve idempotency slot ───────────────────────────────
        UserEntity sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("User", senderId));

        String requestHash = buildRequestHash(senderId, request);
        IdempotencyKeyEntity idempotencyRecord = idempotencyKeyRepository.save(
                IdempotencyKeyEntity.builder()
                        .idempotencyKey(idempotencyKey)
                        .user(sender)
                        .requestHash(requestHash)
                        .status(IdempotencyStatus.PROCESSING)
                        .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(24))
                        .build()
        );

        try {
            TransactionResponse response = executeTransfer(senderId, amount, request);

            // ── Step 9: Complete idempotency key ───────────────────────────
            idempotencyRecord.setStatus(IdempotencyStatus.COMPLETED);
            idempotencyRecord.setResponsePayload(serialize(response));
            idempotencyKeyRepository.save(idempotencyRecord);

            return response;

        } catch (Exception ex) {
            // Mark FAILED so retries can proceed without being blocked by PROCESSING
            idempotencyRecord.setStatus(IdempotencyStatus.FAILED);
            idempotencyKeyRepository.save(idempotencyRecord);
            throw ex;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Core transfer execution (within the outer @Transactional boundary)
    // ──────────────────────────────────────────────────────────────────────

    private TransactionResponse executeTransfer(UUID senderId, BigDecimal amount, TransferRequest request) {

        // ── Step 3: Locate sender's primary wallet (without lock yet) ──────
        List<WalletEntity> senderWallets = walletRepository.findByUserId(senderId);
        if (senderWallets.isEmpty()) {
            throw new ResourceNotFoundException("Wallet", senderId);
        }
        WalletEntity senderWalletRef = senderWallets.get(0);

        // ── Locate target wallet ───────────────────────────────────────────
        WalletEntity targetWalletRef = walletRepository.findByWalletNumber(request.getTargetWalletNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", request.getTargetWalletNumber()));

        // Self-transfer guard
        if (senderWalletRef.getId().equals(targetWalletRef.getId())) {
            throw new IllegalArgumentException("You cannot transfer funds to your own wallet.");
        }

        // ── Step 3 (cont.): Acquire pessimistic locks in canonical UUID order ──
        // Lock ordering: always lock the wallet with the smaller UUID first.
        // This prevents deadlocks when two concurrent transfers involve the same pair of wallets.
        UUID firstId  = senderWalletRef.getId().compareTo(targetWalletRef.getId()) < 0
                ? senderWalletRef.getId() : targetWalletRef.getId();
        UUID secondId = senderWalletRef.getId().compareTo(targetWalletRef.getId()) < 0
                ? targetWalletRef.getId() : senderWalletRef.getId();

        WalletEntity firstLocked = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", firstId));
        WalletEntity secondLocked = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", secondId));

        WalletEntity senderWallet  = firstLocked.getId().equals(senderWalletRef.getId()) ? firstLocked : secondLocked;
        WalletEntity targetWallet  = firstLocked.getId().equals(targetWalletRef.getId())  ? firstLocked : secondLocked;

        // ── Step 4: Validate wallet states ────────────────────────────────
        if (senderWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletFrozenException(senderWallet.getWalletNumber());
        }
        if (targetWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletFrozenException(targetWallet.getWalletNumber());
        }
        if (senderWallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(
                    "Insufficient balance. Available: " + senderWallet.getBalance() + " USD, Requested: " + amount + " USD"
            );
        }

        // ── Resolve ledger accounts ────────────────────────────────────────
        LedgerAccountEntity senderLedgerAccount = ledgerAccountRepository
                .findByWalletId(senderWallet.getId())
                .orElseThrow(() -> new ResourceNotFoundException("LedgerAccount for sender wallet", senderWallet.getId()));

        LedgerAccountEntity targetLedgerAccount = ledgerAccountRepository
                .findByWalletId(targetWallet.getId())
                .orElseThrow(() -> new ResourceNotFoundException("LedgerAccount for target wallet", targetWallet.getId()));

        // ── Step 5: Create PENDING transaction record ─────────────────────
        String referenceCode = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();

        TransactionEntity transaction = transactionRepository.save(
                TransactionEntity.builder()
                        .referenceCode(referenceCode)
                        .sourceWallet(senderWallet)
                        .targetWallet(targetWallet)
                        .amount(amount)
                        .currency("USD")
                        .type(TransactionType.P2P_TRANSFER)
                        .status(TransactionStatus.PENDING)
                        .description(request.getDescription() != null ? request.getDescription() : "P2P Transfer")
                        .build()
        );

        // ── Step 6: Write immutable double-entry ledger postings ──────────
        //   DEBIT  Sender's ledger account  — money leaves sender
        ledgerEntryRepository.save(LedgerEntryEntity.builder()
                .transaction(transaction)
                .ledgerAccount(senderLedgerAccount)
                .direction(EntryDirection.DEBIT)
                .amount(amount)
                .build());

        //   CREDIT Target's ledger account  — money arrives at recipient
        ledgerEntryRepository.save(LedgerEntryEntity.builder()
                .transaction(transaction)
                .ledgerAccount(targetLedgerAccount)
                .direction(EntryDirection.CREDIT)
                .amount(amount)
                .build());

        // ── Step 7: Update wallet balances ────────────────────────────────
        senderWallet.setBalance(senderWallet.getBalance().subtract(amount));
        targetWallet.setBalance(targetWallet.getBalance().add(amount));
        walletRepository.save(senderWallet);
        walletRepository.save(targetWallet);

        // ── Step 8: Mark transaction SETTLED ─────────────────────────────
        transaction.setStatus(TransactionStatus.SETTLED);
        TransactionEntity settled = transactionRepository.save(transaction);

        // ── Outbox: record the domain event in this same DB transaction ──
        // If anything above rolls back, this row rolls back with it — Kafka is
        // never touched here, so a broker outage cannot affect a committed transfer.
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("transactionId", settled.getId());
        eventData.put("referenceCode", settled.getReferenceCode());
        eventData.put("sourceWalletNumber", senderWallet.getWalletNumber());
        eventData.put("targetWalletNumber", targetWallet.getWalletNumber());
        eventData.put("amount", amount);
        eventData.put("currency", settled.getCurrency());
        eventData.put("status", settled.getStatus().name());
        eventData.put("type", settled.getType().name());
        outboxEventWriter.record("Transaction", settled.getId(), "P2P_TRANSFER_SETTLED", eventData);

        log.info("P2P transfer of {} USD settled: {} → {}. Ref: {}",
                amount,
                senderWallet.getWalletNumber(),
                targetWallet.getWalletNumber(),
                referenceCode);

        return walletServiceImpl.toTransactionResponse(settled);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────────────

    private String buildRequestHash(UUID senderId, TransferRequest request) {
        String raw = senderId + "|" + request.getTargetWalletNumber() + "|" + request.getAmount().toPlainString();
        return Integer.toHexString(raw.hashCode());
    }

    private String serialize(TransactionResponse response) {
        try {
            return MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize TransactionResponse for idempotency cache", e);
            return "{}";
        }
    }
}
