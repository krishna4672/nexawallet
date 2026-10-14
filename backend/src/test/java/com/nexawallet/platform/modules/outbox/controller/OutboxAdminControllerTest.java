package com.nexawallet.platform.modules.outbox.controller;

import com.nexawallet.platform.common.security.JwtTokenProvider;
import com.nexawallet.platform.modules.auth.entity.UserEntity;
import com.nexawallet.platform.modules.auth.entity.UserRole;
import com.nexawallet.platform.modules.auth.repository.UserRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerAccountRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerEntryRepository;
import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.entity.OutboxStatus;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import com.nexawallet.platform.modules.transfer.repository.TransactionRepository;
import com.nexawallet.platform.modules.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the outbox admin endpoint is reachable with ROLE_ADMIN and rejected otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Outbox Admin Endpoint — ROLE_ADMIN Access Control")
class OutboxAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private WalletRepository walletRepository;

    @AfterEach
    void cleanUp() {
        // Defensive full-dependency-order cleanup: @SpringBootTest classes with identical config
        // share a cached ApplicationContext (and therefore the same in-memory H2 instance), so this
        // must tolerate wallets/ledger rows left behind by whichever test class ran last, not just
        // the rows this class's own tests created.
        outboxEventRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String tokenFor(String email, UserRole role) {
        UserEntity user = userRepository.save(UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("SecurePass123!"))
                .firstName("Test")
                .lastName(role.name())
                .role(role)
                .active(true)
                .build());
        return jwtTokenProvider.generateToken(user.getId(), user.getEmail(), user.getRole().name());
    }

    @Test
    @DisplayName("A1. ROLE_ADMIN can read outbox stats")
    void adminRole_canReadStats() throws Exception {
        outboxEventRepository.save(OutboxEventEntity.builder()
                .aggregateType("Transaction")
                .aggregateId("agg-1")
                .eventType("DEPOSIT_SETTLED")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                .build());

        String adminToken = tokenFor("admin.outbox@example.com", UserRole.ROLE_ADMIN);

        mockMvc.perform(get("/api/v1/admin/outbox/stats")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCount", org.hamcrest.Matchers.is(1)))
                .andExpect(jsonPath("$.oldestPendingAgeSeconds", org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("A2. ROLE_CUSTOMER is forbidden from reading outbox stats")
    void customerRole_isForbidden() throws Exception {
        String customerToken = tokenFor("customer.outbox@example.com", UserRole.ROLE_CUSTOMER);

        mockMvc.perform(get("/api/v1/admin/outbox/stats")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A3. Unauthenticated request is rejected")
    void unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox/stats"))
                .andExpect(status().isUnauthorized());
    }
}
