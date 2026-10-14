package com.nexawallet.platform.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the rate-limit filter's routing, response-writing, and fail-policy logic.
 * The Redis Lua script itself is covered by {@link RedisSlidingWindowRateLimiterIntegrationTest}
 * (real Redis, Testcontainers) — here {@link RedisSlidingWindowRateLimiter} is mocked so the
 * filter's own decisions can be asserted deterministically without any backend.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter — Unit Tests")
class RateLimitFilterTest {

    @Mock private RedisSlidingWindowRateLimiter limiter;
    @Mock private FilterChain filterChain;

    private RateLimitProperties properties;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        filter = new RateLimitFilter(limiter, properties, new ClientIpResolver(properties), new IdentifierHasher(properties),
                new ObjectMapper().registerModule(new JavaTimeModule()));
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("1. A request within the limit passes through to the controller")
    void withinLimit_passesThrough() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(RateLimitResult.allowed(9));

        MockHttpServletRequest request = loginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200); // untouched by the filter (MockHttpServletResponse defaults to 200)
    }

    @Test
    @DisplayName("2. A request exceeding the limit returns 429 with a Retry-After header and never reaches the controller")
    void overLimit_returns429AndBlocksChain() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(RateLimitResult.rejected(Duration.ofSeconds(37)));

        MockHttpServletRequest request = loginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("37");
        assertThat(response.getContentAsString()).contains("\"status\":429").contains("Too Many Requests");
    }

    @Test
    @DisplayName("3. Redis unavailable with the default CLOSED policy returns 503 and blocks the chain")
    void backendUnavailable_closedPolicy_returns503() throws Exception {
        properties.setFailPolicy(RateLimitProperties.FailPolicy.CLOSED);
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(RateLimitResult.unavailable());

        MockHttpServletRequest request = transferRequestAsUser();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Service Unavailable");
    }

    @Test
    @DisplayName("4. Redis unavailable with the explicit OPEN policy allows the request through")
    void backendUnavailable_openPolicy_allowsThrough() throws Exception {
        properties.setFailPolicy(RateLimitProperties.FailPolicy.OPEN);
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(RateLimitResult.unavailable());

        MockHttpServletRequest request = transferRequestAsUser();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("5. A route with no configured policy passes straight through without calling the limiter")
    void unpoliceRoute_passesThroughWithoutCallingLimiter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(limiter, never()).tryAcquire(any(), anyInt(), anyInt());
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("6. OPTIONS preflight requests bypass rate limiting entirely")
    void optionsPreflight_bypassesRateLimiting() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(limiter, never()).tryAcquire(any(), anyInt(), anyInt());
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("7. Disabling the filter (nexa.ratelimit.enabled=false) bypasses it entirely")
    void disabled_bypassesEntirely() throws Exception {
        properties.setEnabled(false);
        MockHttpServletRequest request = loginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(limiter, never()).tryAcquire(any(), anyInt(), anyInt());
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("8. An authenticated money-movement request checks both the user bucket and the IP bucket")
    void transferRequest_checksUserAndIpBuckets() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(RateLimitResult.allowed(10));

        MockHttpServletRequest request = transferRequestAsUser();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(limiter, times(2)).tryAcquire(keyCaptor.capture(), anyInt(), anyInt());
        assertThat(keyCaptor.getAllValues())
                .contains("transfer:user:11111111-1111-1111-1111-111111111111")
                .anyMatch(key -> key.startsWith("transfer:ip:"));
    }

    private MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.5");
        request.setContent("{\"email\":\"someone@example.com\",\"password\":\"x\"}".getBytes());
        request.setContentType("application/json");
        return request;
    }

    private MockHttpServletRequest transferRequestAsUser() {
        var principal = new com.nexawallet.platform.common.security.CustomUserDetails(
                com.nexawallet.platform.modules.auth.entity.UserEntity.builder()
                        .email("payer@example.com")
                        .passwordHash("hashed")
                        .firstName("Pay")
                        .lastName("Er")
                        .role(com.nexawallet.platform.modules.auth.entity.UserRole.ROLE_CUSTOMER)
                        .active(true)
                        .build());
        setId(principal);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setRemoteAddr("198.51.100.9");
        return request;
    }

    private void setId(com.nexawallet.platform.common.security.CustomUserDetails principal) {
        try {
            var field = principal.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(principal, java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
