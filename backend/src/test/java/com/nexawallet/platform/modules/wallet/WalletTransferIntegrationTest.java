package com.nexawallet.platform.modules.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexawallet.platform.modules.auth.dto.RegisterRequest;
import com.nexawallet.platform.modules.auth.repository.UserRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerAccountRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerEntryRepository;
import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import com.nexawallet.platform.modules.transfer.repository.IdempotencyKeyRepository;
import com.nexawallet.platform.modules.transfer.repository.TransactionRepository;
import com.nexawallet.platform.modules.wallet.dto.DepositRequest;
import com.nexawallet.platform.modules.wallet.dto.TransferRequest;
import com.nexawallet.platform.modules.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Wallet and Transfer APIs.
 * Uses Spring Boot test slice with H2 in-memory database (test profile).
 * Tests the full HTTP → Service → Repository layer stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Wallet & Transfer API — Integration Tests")
class WalletTransferIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void cleanUp() {
        // Clean in FK dependency order
        outboxEventRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Register helper
    // ──────────────────────────────────────────────────────────────────────

    private String registerAndGetToken(String email, String firstName) throws Exception {
        RegisterRequest req = RegisterRequest.builder()
                .firstName(firstName)
                .lastName("Test")
                .email(email)
                .password("SecurePass123!")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Wallet balance
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W1. GET /api/v1/wallet/me returns wallet with zero initial balance")
    void getWallet_returnsZeroBalance_onNewRegistration() throws Exception {
        String token = registerAndGetToken("wallet.test@example.com", "Wallet");

        mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletNumber", notNullValue()))
                .andExpect(jsonPath("$.balance", is(0.0)))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("W2. GET /api/v1/wallet/me returns 401 without authentication")
    void getWallet_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/me"))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Deposit
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W3. POST /api/v1/wallet/deposit adds funds and returns SETTLED transaction")
    void deposit_success_addsBalanceAndReturnsSettledTransaction() throws Exception {
        String token = registerAndGetToken("deposit.user@example.com", "Depositor");

        DepositRequest depositReq = new DepositRequest(new BigDecimal("250.00"), "Test deposit");

        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(depositReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SETTLED")))
                .andExpect(jsonPath("$.type", is("DEPOSIT_SIMULATED")))
                .andExpect(jsonPath("$.amount", is(250.0)))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.referenceCode", notNullValue()));

        // Verify balance updated
        mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(250.0)));
    }

    @Test
    @DisplayName("W4. POST /api/v1/wallet/deposit with zero amount returns 400 Bad Request")
    void deposit_zeroAmount_returns400() throws Exception {
        String token = registerAndGetToken("zero.deposit@example.com", "Zero");

        DepositRequest depositReq = new DepositRequest(new BigDecimal("0.00"), null);

        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(depositReq)))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────────────────────────────────────────────────────────
    // P2P Transfer
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T1. POST /api/v1/transfers succeeds: sender debited, receiver credited")
    void transfer_success_updatesBalancesCorrectly() throws Exception {
        String aliceToken = registerAndGetToken("alice.transfer@example.com", "Alice");
        String bobToken   = registerAndGetToken("bob.transfer@example.com", "Bob");

        // Get Bob's wallet number
        MvcResult bobWalletResult = mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + bobToken))
                .andReturn();
        String bobWalletNumber = objectMapper.readTree(bobWalletResult.getResponse().getContentAsString())
                .get("walletNumber").asText();

        // Fund Alice's wallet
        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("500.00"), "seed"))))
                .andExpect(status().isOk());

        // Alice transfers 150 USD to Bob
        TransferRequest transferReq = new TransferRequest(bobWalletNumber, new BigDecimal("150.00"), "Payment to Bob");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SETTLED")))
                .andExpect(jsonPath("$.type", is("P2P_TRANSFER")))
                .andExpect(jsonPath("$.amount", is(150.0)));

        // Verify Alice's balance is now 350
        mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(350.0)));

        // Verify Bob's balance is now 150
        mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(150.0)));
    }

    @Test
    @DisplayName("T2. Transfer exceeding balance returns 422 Unprocessable Entity")
    void transfer_insufficientBalance_returns422() throws Exception {
        String aliceToken = registerAndGetToken("alice.broke@example.com", "Alice");
        String bobToken   = registerAndGetToken("bob.rich@example.com", "Bob");

        MvcResult bobWalletResult = mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + bobToken))
                .andReturn();
        String bobWalletNumber = objectMapper.readTree(bobWalletResult.getResponse().getContentAsString())
                .get("walletNumber").asText();

        // Alice has zero balance, tries to transfer 100
        TransferRequest transferReq = new TransferRequest(bobWalletNumber, new BigDecimal("100.00"), null);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("Insufficient Balance")));
    }

    @Test
    @DisplayName("T3. Idempotent transfer: second request with same key returns cached SETTLED response")
    void transfer_idempotentRetry_returnsCachedResponse() throws Exception {
        String aliceToken = registerAndGetToken("alice.idempotent@example.com", "Alice");
        String bobToken   = registerAndGetToken("bob.idempotent@example.com", "Bob");

        MvcResult bobWalletResult = mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + bobToken))
                .andReturn();
        String bobWalletNumber = objectMapper.readTree(bobWalletResult.getResponse().getContentAsString())
                .get("walletNumber").asText();

        // Fund Alice
        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("300.00"), "seed"))))
                .andExpect(status().isOk());

        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest transferReq = new TransferRequest(bobWalletNumber, new BigDecimal("100.00"), "Idempotent test");

        // First request
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SETTLED")));

        // Second request with SAME idempotency key — must return same result, not double-charge
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SETTLED")));

        // Alice's balance must still be 200 (only one debit)
        mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(200.0)));
    }

    @Test
    @DisplayName("T4. Transfer without Idempotency-Key header returns 400 Bad Request")
    void transfer_missingIdempotencyKey_returns400() throws Exception {
        String aliceToken = registerAndGetToken("alice.nokey@example.com", "Alice");

        TransferRequest transferReq = new TransferRequest("NX-0000-0000-0000", new BigDecimal("10.00"), null);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T5. Self-transfer is rejected with 400 Bad Request")
    void transfer_selfTransfer_returns400() throws Exception {
        String aliceToken = registerAndGetToken("alice.self@example.com", "Alice");

        MvcResult walletResult = mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + aliceToken))
                .andReturn();
        String aliceWalletNumber = objectMapper.readTree(walletResult.getResponse().getContentAsString())
                .get("walletNumber").asText();

        // Fund Alice first
        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("100.00"), "seed"))))
                .andExpect(status().isOk());

        TransferRequest selfTransferReq = new TransferRequest(aliceWalletNumber, new BigDecimal("50.00"), null);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(selfTransferReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("T6. GET /api/v1/wallet/transactions returns paginated history after deposit and transfer")
    void getTransactionHistory_returnsCorrectCount() throws Exception {
        String aliceToken = registerAndGetToken("alice.history@example.com", "Alice");
        String bobToken   = registerAndGetToken("bob.history@example.com", "Bob");

        MvcResult bobWalletResult = mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + bobToken))
                .andReturn();
        String bobWalletNumber = objectMapper.readTree(bobWalletResult.getResponse().getContentAsString())
                .get("walletNumber").asText();

        // Alice deposits
        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("400.00"), "seed"))))
                .andExpect(status().isOk());

        // Alice transfers to Bob
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(bobWalletNumber, new BigDecimal("100.00"), null))))
                .andExpect(status().isOk());

        // Alice should see 2 transactions (1 deposit + 1 outgoing transfer)
        mockMvc.perform(get("/api/v1/wallet/transactions")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)));

        // Bob should see 1 transaction (1 incoming transfer)
        mockMvc.perform(get("/api/v1/wallet/transactions")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Phase 4: Transactional Outbox
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O1. A successful simulated deposit writes exactly one PENDING outbox event, in the same DB transaction")
    void deposit_success_writesOutboxEvent() throws Exception {
        String token = registerAndGetToken("outbox.deposit@example.com", "Outboxer");

        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("75.00"), "outbox test"))))
                .andExpect(status().isOk());

        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("DEPOSIT_SETTLED");
        assertThat(events.get(0).getAggregateType()).isEqualTo("Transaction");
        assertThat(events.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(events.get(0).getPayload()).contains("\"schemaVersion\":1").contains("DEPOSIT_SETTLED");
    }

    @Test
    @DisplayName("O2. A successful P2P transfer writes exactly one PENDING outbox event")
    void transfer_success_writesOutboxEvent() throws Exception {
        String aliceToken = registerAndGetToken("outbox.alice@example.com", "Alice");
        String bobToken   = registerAndGetToken("outbox.bob@example.com", "Bob");

        MvcResult bobWalletResult = mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + bobToken))
                .andReturn();
        String bobWalletNumber = objectMapper.readTree(bobWalletResult.getResponse().getContentAsString())
                .get("walletNumber").asText();

        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("200.00"), "seed"))))
                .andExpect(status().isOk());

        // Deposit above already created 1 outbox event — clear it so this test isolates the transfer's event.
        outboxEventRepository.deleteAll();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(bobWalletNumber, new BigDecimal("40.00"), "outbox transfer test"))))
                .andExpect(status().isOk());

        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("P2P_TRANSFER_SETTLED");
        assertThat(events.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("O3. A transfer rejected for insufficient balance writes no outbox event (rolls back with everything else)")
    void transfer_insufficientBalance_writesNoOutboxEvent() throws Exception {
        String aliceToken = registerAndGetToken("outbox.broke@example.com", "Alice");
        String bobToken   = registerAndGetToken("outbox.rich@example.com", "Bob");

        MvcResult bobWalletResult = mockMvc.perform(get("/api/v1/wallet/me")
                        .header("Authorization", "Bearer " + bobToken))
                .andReturn();
        String bobWalletNumber = objectMapper.readTree(bobWalletResult.getResponse().getContentAsString())
                .get("walletNumber").asText();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(bobWalletNumber, new BigDecimal("500.00"), null))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(outboxEventRepository.findAll()).isEmpty();
    }
}
