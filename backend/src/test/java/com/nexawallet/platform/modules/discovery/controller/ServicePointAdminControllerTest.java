package com.nexawallet.platform.modules.discovery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexawallet.platform.common.security.JwtTokenProvider;
import com.nexawallet.platform.modules.auth.entity.UserEntity;
import com.nexawallet.platform.modules.auth.entity.UserRole;
import com.nexawallet.platform.modules.auth.repository.UserRepository;
import com.nexawallet.platform.modules.discovery.dto.ServicePointCreateRequest;
import com.nexawallet.platform.modules.discovery.dto.ServicePointUpdateRequest;
import com.nexawallet.platform.modules.discovery.entity.ServicePointType;
import com.nexawallet.platform.modules.discovery.repository.ServicePointRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerAccountRepository;
import com.nexawallet.platform.modules.ledger.repository.LedgerEntryRepository;
import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import com.nexawallet.platform.modules.transfer.repository.TransactionRepository;
import com.nexawallet.platform.modules.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real (H2-backed) integration tests for the admin service-point endpoints: ROLE_ADMIN enforcement,
 * outbox event creation on write, and controlled 503 behavior for reindex when Elasticsearch is
 * unreachable (the "test" profile points it at a deliberately unreachable host — see application-test.yml).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Service Point Admin Endpoints — Integration Tests")
class ServicePointAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ServicePointRepository servicePointRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;
    @Autowired private WalletRepository walletRepository;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        servicePointRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String tokenFor(String email, UserRole role) {
        UserEntity user = userRepository.save(UserEntity.builder()
                .email(email).passwordHash(passwordEncoder.encode("SecurePass123!"))
                .firstName("Test").lastName(role.name()).role(role).active(true).build());
        return jwtTokenProvider.generateToken(user.getId(), user.getEmail(), user.getRole().name());
    }

    private String createRequestJson() throws Exception {
        return objectMapper.writeValueAsString(ServicePointCreateRequest.builder()
                .name("Downtown ATM").type(ServicePointType.ATM).latitude(40.0).longitude(-73.0).address("123 Main St")
                .build());
    }

    @Test
    @DisplayName("1. ROLE_ADMIN can create a service point; it persists and writes exactly one outbox event")
    void adminRole_canCreateServicePoint() throws Exception {
        String adminToken = tokenFor("admin.sp1@example.com", UserRole.ROLE_ADMIN);

        mockMvc.perform(post("/api/v1/admin/service-points")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Downtown ATM")))
                .andExpect(jsonPath("$.geohash", notNullValue()))
                .andExpect(jsonPath("$.active", is(true)));

        assertThat(servicePointRepository.findAll()).hasSize(1);
        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAggregateType()).isEqualTo("ServicePoint");
        assertThat(events.get(0).getEventType()).isEqualTo("SERVICE_POINT_CREATED");
    }

    @Test
    @DisplayName("2. ROLE_CUSTOMER is forbidden from creating a service point")
    void customerRole_isForbidden() throws Exception {
        String customerToken = tokenFor("customer.sp1@example.com", UserRole.ROLE_CUSTOMER);

        mockMvc.perform(post("/api/v1/admin/service-points")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isForbidden());

        assertThat(servicePointRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("3. Unauthenticated request is rejected")
    void unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/admin/service-points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("4. PATCH active=false deactivates and records a SERVICE_POINT_DEACTIVATED outbox event")
    void adminRole_canDeactivate() throws Exception {
        String adminToken = tokenFor("admin.sp2@example.com", UserRole.ROLE_ADMIN);

        MvcResult created = mockMvc.perform(post("/api/v1/admin/service-points")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        outboxEventRepository.deleteAll(); // isolate the PATCH's own event

        mockMvc.perform(patch("/api/v1/admin/service-points/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ServicePointUpdateRequest.builder().active(false).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));

        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("SERVICE_POINT_DEACTIVATED");
    }

    @Test
    @DisplayName("5. PATCH on a non-existent id returns 404")
    void update_nonExistentId_returns404() throws Exception {
        String adminToken = tokenFor("admin.sp3@example.com", UserRole.ROLE_ADMIN);

        mockMvc.perform(patch("/api/v1/admin/service-points/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ServicePointUpdateRequest.builder().name("x").build())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("6. Reindex returns a controlled 503 (not a stack trace) when Elasticsearch is unreachable")
    void reindex_elasticsearchUnreachable_returns503() throws Exception {
        String adminToken = tokenFor("admin.sp4@example.com", UserRole.ROLE_ADMIN);

        // Reindexing an empty table never touches Elasticsearch at all (nothing to upsert) and
        // would trivially "succeed" with zero documents — seed one row so the job actually reaches
        // the (unreachable, in this profile) Elasticsearch client and surfaces the 503.
        mockMvc.perform(post("/api/v1/admin/service-points")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/service-points/reindex")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("7. Invalid create request (missing name) returns 400")
    void create_invalidRequest_returns400() throws Exception {
        String adminToken = tokenFor("admin.sp5@example.com", UserRole.ROLE_ADMIN);
        String invalidJson = "{\"type\":\"ATM\",\"latitude\":40.0,\"longitude\":-73.0}";

        mockMvc.perform(post("/api/v1/admin/service-points")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}
