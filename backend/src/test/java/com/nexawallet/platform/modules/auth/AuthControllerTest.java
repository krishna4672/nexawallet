package com.nexawallet.platform.modules.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexawallet.platform.common.security.JwtTokenProvider;
import com.nexawallet.platform.modules.auth.dto.LoginRequest;
import com.nexawallet.platform.modules.auth.dto.RegisterRequest;
import com.nexawallet.platform.modules.auth.entity.UserEntity;
import com.nexawallet.platform.modules.auth.repository.UserRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerAccountRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerEntryRepository;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import com.nexawallet.platform.modules.transfer.repository.TransactionRepository;
import com.nexawallet.platform.modules.wallet.dto.DepositRequest;
import com.nexawallet.platform.modules.wallet.entity.WalletEntity;
import com.nexawallet.platform.modules.wallet.repository.WalletRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        // Clear repositories in proper foreign-key dependency order. Deposits made in the Phase 5
        // cache-freshness tests below write ledger entries/transactions/outbox rows, so those must
        // be cleared before ledger_accounts/wallets/users or the next test's cleanup violates FKs.
        outboxEventRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. User registration successfully creates account, wallet, and returns JWT")
    void testSuccessfulRegistration() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .firstName("Elena")
                .lastName("Rostova")
                .email("elena.rostova@example.com")
                .password("SecureFintechPass123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.email", is("elena.rostova@example.com")))
                .andExpect(jsonPath("$.user.firstName", is("Elena")))
                .andExpect(jsonPath("$.user.lastName", is("Rostova")))
                .andExpect(jsonPath("$.user.role", is("ROLE_CUSTOMER")))
                .andExpect(jsonPath("$.user.walletNumber", notNullValue()))
                .andExpect(jsonPath("$.user.currency", is("USD")))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());

        // Verify database state
        UserEntity savedUser = userRepository.findByEmail("elena.rostova@example.com").orElse(null);
        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getFirstName()).isEqualTo("Elena");

        List<WalletEntity> wallets = walletRepository.findByUserId(savedUser.getId());
        assertThat(wallets).hasSize(1);
        assertThat(wallets.get(0).getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("2. Duplicate email registration is rejected with HTTP 409 Conflict")
    void testDuplicateEmailRejection() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .firstName("David")
                .lastName("Miller")
                .email("david.miller@example.com")
                .password("Password123!")
                .build();

        // First registration succeeds
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Duplicate registration with uppercase casing rejected with 409 Conflict
        RegisterRequest duplicateRequest = RegisterRequest.builder()
                .firstName("Dave")
                .lastName("Miller")
                .email("DAVID.MILLER@EXAMPLE.COM")
                .password("AnotherPassword123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("already registered")));
    }

    @Test
    @DisplayName("3. Passwords are never stored in plaintext and match BCrypt encoding")
    void testPasswordHashing() throws Exception {
        String rawPassword = "TopSecretPassword99!";
        RegisterRequest request = RegisterRequest.builder()
                .firstName("Grace")
                .lastName("Hopper")
                .email("grace.hopper@example.com")
                .password(rawPassword)
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        UserEntity user = userRepository.findByEmail("grace.hopper@example.com").orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(user.getPasswordHash()).startsWith("$2a$");
        assertThat(passwordEncoder.matches(rawPassword, user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("4. Successful login returns HTTP 200 with JWT access token and user profile")
    void testSuccessfulLogin() throws Exception {
        // Register user
        RegisterRequest registerRequest = RegisterRequest.builder()
                .firstName("Marcus")
                .lastName("Aurelius")
                .email("marcus@philosophy.org")
                .password("StoicPass2026!")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Login with correct credentials
        LoginRequest loginRequest = LoginRequest.builder()
                .email("marcus@philosophy.org")
                .password("StoicPass2026!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.email", is("marcus@philosophy.org")))
                .andExpect(jsonPath("$.user.firstName", is("Marcus")))
                .andExpect(jsonPath("$.user.walletNumber", notNullValue()));
    }

    @Test
    @DisplayName("5. Login with invalid password returns HTTP 401 Unauthorized")
    void testLoginWithInvalidCredentials() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .firstName("Nikola")
                .lastName("Tesla")
                .email("nikola.tesla@science.com")
                .password("AlternatingCurrent1!")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Login with incorrect password
        LoginRequest invalidLogin = LoginRequest.builder()
                .email("nikola.tesla@science.com")
                .password("WrongPassword123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidLogin)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", is("Invalid email or password")));
    }

    @Test
    @DisplayName("6. Accessing protected endpoint without Bearer token returns HTTP 401 Unauthorized")
    void testProtectedEndpointWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("7. Accessing protected endpoint with valid Bearer token returns HTTP 200 and profile")
    void testProtectedEndpointWithValidTokenReturnsOk() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .firstName("Ada")
                .lastName("Lovelace")
                .email("ada.lovelace@computing.org")
                .password("AnalyticalEngine1!")
                .build();

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = registerResult.getResponse().getContentAsString();
        String token = objectMapper.readTree(responseBody).get("accessToken").asText();

        // Access protected endpoint /api/v1/auth/me with Bearer token
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("ada.lovelace@computing.org")))
                .andExpect(jsonPath("$.firstName", is("Ada")))
                .andExpect(jsonPath("$.lastName", is("Lovelace")))
                .andExpect(jsonPath("$.role", is("ROLE_CUSTOMER")))
                .andExpect(jsonPath("$.walletNumber", notNullValue()));
    }

    @Test
    @DisplayName("8. Accessing protected endpoint with invalid or expired JWT returns HTTP 401 Unauthorized")
    void testProtectedEndpointWithInvalidOrExpiredTokenReturnsUnauthorized() throws Exception {
        // Test with malformed token
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer invalid.malformed.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));

        // Test with expired token
        String expiredToken = createExpiredToken(UUID.randomUUID(), "expired@test.com");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Phase 5: Read-through cache for the /api/v1/auth/me identity fields
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("9. Two users' cached /api/v1/auth/me profiles never leak into one another")
    void cachedProfiles_areIsolatedAcrossUsers() throws Exception {
        String tokenA = registerAndGetToken("cache.userA@example.com", "Alice", "Anderson");
        String tokenB = registerAndGetToken("cache.userB@example.com", "Brianna", "Baxter");

        // Warm both caches (first call is a miss-then-populate for each user). Registration
        // normalizes email to lowercase, so assert against the normalized form.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("cache.usera@example.com")))
                .andExpect(jsonPath("$.firstName", is("Alice")));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("cache.userb@example.com")))
                .andExpect(jsonPath("$.firstName", is("Brianna")));

        // Repeat (now served from cache) — must still return each user's own data, never the other's
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("cache.usera@example.com")))
                .andExpect(jsonPath("$.firstName", is("Alice")));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("cache.userb@example.com")))
                .andExpect(jsonPath("$.firstName", is("Brianna")));
    }

    @Test
    @DisplayName("10. A cached /api/v1/auth/me response never serves a stale wallet balance")
    void cachedProfile_neverServesStaleBalance() throws Exception {
        String token = registerAndGetToken("cache.freshbalance@example.com", "Fresh", "Balance");

        // Warm the identity-fields cache with a zero balance
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").doesNotExist()) // no "balance" field; it's "walletBalance"
                .andExpect(jsonPath("$.walletBalance", is(0.0)));

        // Deposit funds — this does not touch the identity cache at all
        mockMvc.perform(post("/api/v1/wallet/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("42.00"), "cache freshness test"))))
                .andExpect(status().isOk());

        // The identity portion is very likely still a cache hit, but the balance must be fresh
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletBalance", is(42.0)));
    }

    private String registerAndGetToken(String email, String firstName, String lastName) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .password("SecurePass123!")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String createExpiredToken(UUID userId, String email) {
        String testSecret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
        Date now = new Date();
        Date pastExpiry = new Date(now.getTime() - 3600000); // 1 hour ago

        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", "ROLE_CUSTOMER")
                .issuedAt(new Date(now.getTime() - 7200000))
                .expiration(pastExpiry)
                .signWith(Keys.hmacShaKeyFor(testSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
