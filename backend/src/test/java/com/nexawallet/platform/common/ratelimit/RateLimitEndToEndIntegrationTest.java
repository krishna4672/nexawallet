package com.nexawallet.platform.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexawallet.platform.common.security.JwtTokenProvider;
import com.nexawallet.platform.modules.auth.dto.RegisterRequest;
import com.nexawallet.platform.modules.transfer.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end rate-limiting test: real Spring Security filter chain, real HTTP responses, real
 * Redis (Testcontainers) — proving a blocked request never reaches the financial service, not just
 * that the filter's internal logic branches correctly (see {@link RateLimitFilterTest} for that).
 *
 * Keeps {@code @ActiveProfiles("test")} for H2/Postgres-equivalent + disabled Kafka as usual, but
 * overrides {@code nexa.ratelimit.*} and {@code spring.data.redis.*} via {@code @DynamicPropertySource}
 * to point the limiter at a real broker for this class only. {@code disabledWithoutDocker = true}
 * reports every test here SKIPPED (not failed) when no Docker daemon is reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Rate Limiting — End-to-End (real Redis + real filter chain)")
class RateLimitEndToEndIntegrationTest {

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("nexa.ratelimit.enabled", () -> "true");
        registry.add("nexa.ratelimit.fail-policy", () -> "CLOSED");
        registry.add("nexa.ratelimit.login-ip.limit", () -> "3");
        registry.add("nexa.ratelimit.login-ip.window-seconds", () -> "60");
        registry.add("nexa.ratelimit.deposit-user.limit", () -> "1");
        registry.add("nexa.ratelimit.deposit-user.window-seconds", () -> "60");
        registry.add("nexa.ratelimit.deposit-ip.limit", () -> "100");
        registry.add("nexa.ratelimit.deposit-ip.window-seconds", () -> "60");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private com.nexawallet.platform.modules.auth.repository.UserRepository userRepository;
    @Autowired private com.nexawallet.platform.modules.wallet.repository.WalletRepository walletRepository;
    @Autowired private com.nexawallet.platform.modules.ledger.repository.LedgerAccountRepository ledgerAccountRepository;
    @Autowired private com.nexawallet.platform.modules.ledger.repository.LedgerEntryRepository ledgerEntryRepository;

    @AfterEach
    void cleanUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String registerAndGetToken(String email) throws Exception {
        RegisterRequest req = RegisterRequest.builder()
                .firstName("Rate").lastName("Limited").email(email).password("SecurePass123!").build();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("1. Flooding POST /api/v1/auth/login beyond the IP limit eventually returns 429 with Retry-After")
    void login_floodedBeyondLimit_returns429() throws Exception {
        String body = objectMapper.writeValueAsString(
                com.nexawallet.platform.modules.auth.dto.LoginRequest.builder()
                        .email("nobody@example.com").password("wrong").build());

        // limit is 3 per the dynamic property above
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized()); // wrong credentials, but NOT rate-limited yet
        }

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.error", is("Too Many Requests")))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After")).isNotNull());
    }

    @Test
    @DisplayName("2. A deposit blocked by the per-user limit never reaches WalletService — no transaction row is created")
    void depositBlockedByRateLimit_neverReachesFinancialService() throws Exception {
        String token = registerAndGetToken("ratelimited.depositor@example.com");
        String depositBody = objectMapper.writeValueAsString(
                new com.nexawallet.platform.modules.wallet.dto.DepositRequest(new BigDecimal("10.00"), "rl test"));

        // limit is 1 per the dynamic property above
        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody))
                .andExpect(status().isTooManyRequests());

        // Exactly one deposit was ever recorded — the second HTTP call never reached WalletServiceImpl.
        assertThat(transactionRepository.findAll()).hasSize(1);
    }
}
