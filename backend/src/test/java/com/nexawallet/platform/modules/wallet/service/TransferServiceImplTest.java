package com.nexawallet.platform.modules.wallet.service;

import com.nexawallet.platform.common.exception.DuplicateTransferException;
import com.nexawallet.platform.common.exception.InsufficientBalanceException;
import com.nexawallet.platform.common.exception.WalletFrozenException;
import com.nexawallet.platform.modules.auth.entity.UserEntity;
import com.nexawallet.platform.modules.auth.entity.UserRole;
import com.nexawallet.platform.modules.auth.repository.UserRepository;
import com.nexawallet.platform.modules.ledger.entity.LedgerAccountEntity;
import com.nexawallet.platform.modules.ledger.entity.LedgerAccountType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TransferServiceImpl — the core fintech engine.
 *
 * All external dependencies are mocked with Mockito.
 * These tests verify business logic in isolation without a running database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferServiceImpl — Unit Tests")
class TransferServiceImplTest {

    // ── Mocks ──────────────────────────────────────────────────────────────
    @Mock private WalletRepository walletRepository;
    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock private UserRepository userRepository;

    // WalletServiceImpl is a real spy so we can test toTransactionResponse()
    @Mock private WalletServiceImpl walletServiceImpl;
    @Mock private OutboxEventWriter outboxEventWriter;

    @InjectMocks private TransferServiceImpl transferService;

    // ── Test data ──────────────────────────────────────────────────────────
    private UUID senderId;
    private UUID receiverId;
    private UUID senderWalletId;
    private UUID receiverWalletId;
    private WalletEntity senderWallet;
    private WalletEntity receiverWallet;
    private LedgerAccountEntity senderLedgerAccount;
    private LedgerAccountEntity receiverLedgerAccount;
    private UserEntity senderUser;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        senderId   = UUID.fromString("00000000-0000-0000-0000-000000000001");
        receiverId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        // Ensure sender UUID < receiver UUID so lock order is deterministic
        senderWalletId   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        receiverWalletId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        idempotencyKey = UUID.randomUUID().toString();

        senderUser = UserEntity.builder()
                .email("alice@nexawallet.com")
                .role(UserRole.ROLE_CUSTOMER)
                .firstName("Alice")
                .lastName("Sender")
                .passwordHash("hashed")
                .active(true)
                .build();
        setId(senderUser, senderId);

        senderWallet = WalletEntity.builder()
                .walletNumber("NX-1000-0000-0001")
                .currency("USD")
                .balance(new BigDecimal("500.0000"))
                .status(WalletStatus.ACTIVE)
                .build();
        setId(senderWallet, senderWalletId);

        receiverWallet = WalletEntity.builder()
                .walletNumber("NX-2000-0000-0002")
                .currency("USD")
                .balance(new BigDecimal("100.0000"))
                .status(WalletStatus.ACTIVE)
                .build();
        setId(receiverWallet, receiverWalletId);

        senderLedgerAccount = LedgerAccountEntity.builder()
                .accountType(LedgerAccountType.USER_WALLET)
                .wallet(senderWallet)
                .currency("USD")
                .build();

        receiverLedgerAccount = LedgerAccountEntity.builder()
                .accountType(LedgerAccountType.USER_WALLET)
                .wallet(receiverWallet)
                .currency("USD")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Happy path
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Successful P2P transfer debits sender and credits receiver atomically")
    void transfer_success_debitsAndCreditsCorrectly() {
        BigDecimal amount = new BigDecimal("200.00");
        TransferRequest request = new TransferRequest("NX-2000-0000-0002", amount, "Rent payment");

        mockNoExistingIdempotencyKey();
        mockUserAndWallets();
        mockLedgerAccounts();
        TransactionEntity savedTx = buildSettledTransaction(amount);
        when(transactionRepository.save(any())).thenReturn(savedTx);
        when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse mockResponse = buildMockResponse(savedTx);
        when(walletServiceImpl.toTransactionResponse(any())).thenReturn(mockResponse);

        TransactionResponse result = transferService.transfer(senderId, idempotencyKey, request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SETTLED");
        assertThat(result.getType()).isEqualTo("P2P_TRANSFER");

        // Verify balances were updated
        verify(walletRepository, times(2)).save(any(WalletEntity.class));

        // Verify two immutable ledger entries were written
        verify(ledgerEntryRepository, times(2)).save(any());

        // Verify idempotency key completed
        ArgumentCaptor<IdempotencyKeyEntity> captor = ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
        verify(idempotencyKeyRepository, times(2)).save(captor.capture());
        IdempotencyKeyEntity finalIdempotencyRecord = captor.getAllValues().get(1);
        assertThat(finalIdempotencyRecord.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(finalIdempotencyRecord.getResponsePayload()).isNotBlank();

        // Outbox: a domain event must be recorded for the settled transfer, in-transaction
        verify(outboxEventWriter, times(1)).record(
                eq("Transaction"), any(), eq("P2P_TRANSFER_SETTLED"), any());
    }

    @Test
    @DisplayName("2. Transfer with exact balance succeeds (boundary condition)")
    void transfer_exactBalance_succeeds() {
        BigDecimal amount = new BigDecimal("500.00"); // exactly equals senderWallet balance
        TransferRequest request = new TransferRequest("NX-2000-0000-0002", amount, null);

        mockNoExistingIdempotencyKey();
        mockUserAndWallets();
        mockLedgerAccounts();
        TransactionEntity savedTx = buildSettledTransaction(amount);
        when(transactionRepository.save(any())).thenReturn(savedTx);
        when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletServiceImpl.toTransactionResponse(any())).thenReturn(buildMockResponse(savedTx));

        TransactionResponse result = transferService.transfer(senderId, idempotencyKey, request);

        assertThat(result).isNotNull();
        verify(walletRepository, times(2)).save(any());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Insufficient balance
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3. Transfer rejected when sender balance is insufficient")
    void transfer_insufficientBalance_throwsInsufficientBalanceException() {
        BigDecimal amount = new BigDecimal("999.00"); // more than 500.00 balance
        TransferRequest request = new TransferRequest("NX-2000-0000-0002", amount, null);

        mockNoExistingIdempotencyKey();
        mockUserAndWallets();
        when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> transferService.transfer(senderId, idempotencyKey, request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient balance");

        // No ledger entries or transaction records should have been persisted
        verify(transactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).save(any());

        // No outbox event may be recorded for a transfer that never settled
        verify(outboxEventWriter, never()).record(any(), any(), any(), any());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Self-transfer guard
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("4. Self-transfer is rejected with IllegalArgumentException")
    void transfer_selfTransfer_throwsIllegalArgumentException() {
        TransferRequest request = new TransferRequest("NX-1000-0000-0001", new BigDecimal("50.00"), null);

        mockNoExistingIdempotencyKey();
        when(userRepository.findById(senderId)).thenReturn(Optional.of(senderUser));
        when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.findByUserId(senderId)).thenReturn(List.of(senderWallet));
        when(walletRepository.findByWalletNumber("NX-1000-0000-0001")).thenReturn(Optional.of(senderWallet));

        assertThatThrownBy(() -> transferService.transfer(senderId, idempotencyKey, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot transfer funds to your own wallet");

        verify(transactionRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Frozen wallet
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5. Transfer from a FROZEN sender wallet is rejected")
    void transfer_frozenSenderWallet_throwsWalletFrozenException() {
        senderWallet.setStatus(WalletStatus.FROZEN);
        TransferRequest request = new TransferRequest("NX-2000-0000-0002", new BigDecimal("50.00"), null);

        mockNoExistingIdempotencyKey();
        mockUserAndWallets();
        when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> transferService.transfer(senderId, idempotencyKey, request))
                .isInstanceOf(WalletFrozenException.class);

        verify(transactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("6. Transfer to a FROZEN target wallet is rejected")
    void transfer_frozenTargetWallet_throwsWalletFrozenException() {
        receiverWallet.setStatus(WalletStatus.FROZEN);
        TransferRequest request = new TransferRequest("NX-2000-0000-0002", new BigDecimal("50.00"), null);

        mockNoExistingIdempotencyKey();
        mockUserAndWallets();
        when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> transferService.transfer(senderId, idempotencyKey, request))
                .isInstanceOf(WalletFrozenException.class);

        verify(transactionRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Idempotency
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("7. Duplicate transfer with COMPLETED idempotency key throws DuplicateTransferException with cached payload")
    void transfer_completedIdempotencyKey_throwsDuplicateTransferException() {
        String cachedJson = "{\"transactionId\":\"abc\",\"status\":\"SETTLED\"}";
        IdempotencyKeyEntity existingKey = IdempotencyKeyEntity.builder()
                .idempotencyKey(idempotencyKey)
                .user(senderUser)
                .requestHash("hash")
                .status(IdempotencyStatus.COMPLETED)
                .responsePayload(cachedJson)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(24))
                .build();

        when(idempotencyKeyRepository.findByIdempotencyKeyAndUserId(idempotencyKey, senderId))
                .thenReturn(Optional.of(existingKey));

        TransferRequest request = new TransferRequest("NX-2000-0000-0002", new BigDecimal("100.00"), null);

        assertThatThrownBy(() -> transferService.transfer(senderId, idempotencyKey, request))
                .isInstanceOf(DuplicateTransferException.class)
                .satisfies(ex -> {
                    DuplicateTransferException dup = (DuplicateTransferException) ex;
                    assertThat(dup.getCachedPayload()).isEqualTo(cachedJson);
                });

        // No new transaction or ledger entries should be created
        verify(transactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("8. Concurrent in-flight transfer (PROCESSING key) throws IllegalStateException")
    void transfer_processingIdempotencyKey_throwsIllegalStateException() {
        IdempotencyKeyEntity processingKey = IdempotencyKeyEntity.builder()
                .idempotencyKey(idempotencyKey)
                .user(senderUser)
                .requestHash("hash")
                .status(IdempotencyStatus.PROCESSING)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(24))
                .build();

        when(idempotencyKeyRepository.findByIdempotencyKeyAndUserId(idempotencyKey, senderId))
                .thenReturn(Optional.of(processingKey));

        TransferRequest request = new TransferRequest("NX-2000-0000-0002", new BigDecimal("100.00"), null);

        assertThatThrownBy(() -> transferService.transfer(senderId, idempotencyKey, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");

        verify(transactionRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private void mockNoExistingIdempotencyKey() {
        when(idempotencyKeyRepository.findByIdempotencyKeyAndUserId(anyString(), any()))
                .thenReturn(Optional.empty());
    }

    private void mockUserAndWallets() {
        when(userRepository.findById(senderId)).thenReturn(Optional.of(senderUser));
        when(walletRepository.findByUserId(senderId)).thenReturn(List.of(senderWallet));
        when(walletRepository.findByWalletNumber("NX-2000-0000-0002")).thenReturn(Optional.of(receiverWallet));

        // Lock order: senderWalletId (aaa...) < receiverWalletId (bbb...)
        when(walletRepository.findByIdForUpdate(senderWalletId)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(receiverWalletId)).thenReturn(Optional.of(receiverWallet));
    }

    private void mockLedgerAccounts() {
        when(ledgerAccountRepository.findByWalletId(senderWalletId)).thenReturn(Optional.of(senderLedgerAccount));
        when(ledgerAccountRepository.findByWalletId(receiverWalletId)).thenReturn(Optional.of(receiverLedgerAccount));
    }

    private TransactionEntity buildSettledTransaction(BigDecimal amount) {
        TransactionEntity tx = TransactionEntity.builder()
                .referenceCode("TXN-TEST-001")
                .sourceWallet(senderWallet)
                .targetWallet(receiverWallet)
                .amount(amount)
                .currency("USD")
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.SETTLED)
                .description("Test transfer")
                .build();
        return tx;
    }

    private TransactionResponse buildMockResponse(TransactionEntity tx) {
        return TransactionResponse.builder()
                .transactionId(UUID.randomUUID())
                .referenceCode(tx.getReferenceCode())
                .type(tx.getType().name())
                .status(tx.getStatus().name())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .sourceWalletNumber(senderWallet.getWalletNumber())
                .targetWalletNumber(receiverWallet.getWalletNumber())
                .build();
    }

    /**
     * Uses reflection to set the JPA-managed {@code id} field on a {@link com.nexawallet.platform.common.domain.BaseEntity}
     * subclass. This bypasses the protected setter since IDs are normally only assigned by the DB.
     */
    private void setId(Object entity, UUID id) {
        try {
            var field = com.nexawallet.platform.common.domain.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set entity ID via reflection", e);
        }
    }
}
