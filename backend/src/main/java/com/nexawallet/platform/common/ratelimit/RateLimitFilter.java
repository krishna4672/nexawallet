package com.nexawallet.platform.common.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexawallet.platform.common.exception.ApiErrorResponse;
import com.nexawallet.platform.common.security.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Enforces the distributed sliding-window rate-limit policies on exactly four sensitive routes —
 * {@code POST /api/v1/auth/login}, {@code POST /api/v1/auth/register}, {@code POST /api/v1/transfers},
 * and {@code POST /api/v1/wallet/deposit}. Every other request (health, actuator, swagger, OPTIONS
 * preflight, and any route not explicitly listed here) passes straight through untouched.
 *
 * Registered via {@code SecurityConfig#addFilterAfter(this, JwtAuthenticationFilter.class)} — it
 * runs after JWT authentication has populated the {@link org.springframework.security.core.context.SecurityContext}
 * (so the authenticated user id is available for the money-movement policies) but still inside the
 * security filter chain, well before the controller or any financial service method executes. A
 * rejected request (429 or, on a Redis outage with the CLOSED fail policy, 503) returns directly
 * from this filter and never calls {@code filterChain.doFilter(...)} — the controller is never reached.
 *
 * This filter is deliberately NOT auto-registered as a plain servlet-container filter (see the
 * {@code FilterRegistrationBean} with {@code setEnabled(false)} in {@code SecurityConfig}) — if it
 * were, Spring Boot's default "any Filter bean gets its own container registration" behavior would
 * run it a second time outside Spring Security's chain, silently consuming two quota units per
 * request and corrupting the limit.
 */
@org.springframework.stereotype.Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RedisSlidingWindowRateLimiter limiter;
    private final RateLimitProperties properties;
    private final ClientIpResolver ipResolver;
    private final IdentifierHasher identifierHasher;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RedisSlidingWindowRateLimiter limiter,
            RateLimitProperties properties,
            ClientIpResolver ipResolver,
            IdentifierHasher identifierHasher,
            ObjectMapper objectMapper
    ) {
        this.limiter = limiter;
        this.properties = properties;
        this.ipResolver = ipResolver;
        this.identifierHasher = identifierHasher;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (!properties.isEnabled() || HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        boolean isPost = HttpMethod.POST.matches(request.getMethod());
        HttpServletRequest effectiveRequest = request;
        List<Bucket> buckets;

        if (isPost && "/api/v1/auth/login".equals(path)) {
            ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
            effectiveRequest = wrapped;
            buckets = new ArrayList<>();
            buckets.add(new Bucket("login:ip:" + ipResolver.resolve(request), properties.getLoginIp()));
            String normalizedEmail = extractNormalizedEmail(wrapped);
            if (normalizedEmail != null) {
                buckets.add(new Bucket("login:id:" + identifierHasher.hash(normalizedEmail), properties.getLoginIdentifier()));
            }
        } else if (isPost && "/api/v1/auth/register".equals(path)) {
            buckets = List.of(new Bucket("register:ip:" + ipResolver.resolve(request), properties.getRegisterIp()));
        } else if (isPost && "/api/v1/transfers".equals(path)) {
            buckets = authenticatedBuckets("transfer", request, properties.getTransferUser(), properties.getTransferIp());
        } else if (isPost && "/api/v1/wallet/deposit".equals(path)) {
            buckets = authenticatedBuckets("deposit", request, properties.getDepositUser(), properties.getDepositIp());
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        for (Bucket bucket : buckets) {
            RateLimitResult result = limiter.tryAcquire(bucket.key(), bucket.policy().getLimit(), bucket.policy().getWindowSeconds());

            if (result.backendUnavailable()) {
                if (properties.getFailPolicy() == RateLimitProperties.FailPolicy.OPEN) {
                    log.warn("Rate limiter backend unavailable for '{}' — failPolicy=OPEN, allowing request through", bucket.category());
                    continue;
                }
                log.error("Rate limiter backend unavailable for '{}' — failPolicy=CLOSED, rejecting request", bucket.category());
                writeServiceUnavailable(response, request);
                return;
            }

            if (!result.allowed()) {
                log.info("Rate limit exceeded for '{}'", bucket.category());
                writeTooManyRequests(response, request, result);
                return;
            }
        }

        filterChain.doFilter(effectiveRequest, response);
    }

    private List<Bucket> authenticatedBuckets(String action, HttpServletRequest request,
                                               RateLimitProperties.Policy userPolicy, RateLimitProperties.Policy ipPolicy) {
        List<Bucket> buckets = new ArrayList<>();
        UUID userId = currentUserId();
        if (userId != null) {
            buckets.add(new Bucket(action + ":user:" + userId, userPolicy));
        }
        // IP control supplements — never replaces — the per-user limit above (per spec); it still
        // applies even when there is no authenticated user (e.g. a request with no/invalid token,
        // which Spring Security's AuthorizationFilter will separately reject with 401 afterwards).
        buckets.add(new Bucket(action + ":ip:" + ipResolver.resolve(request), ipPolicy));
        return buckets;
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails principal) {
            return principal.getUserId();
        }
        return null;
    }

    private String extractNormalizedEmail(ContentCachingRequestWrapper wrapped) {
        try {
            byte[] body = StreamUtils.copyToByteArray(wrapped.getInputStream());
            if (body.length == 0) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode emailNode = root.get("email");
            if (emailNode == null || !emailNode.isTextual()) {
                return null;
            }
            return emailNode.asText().toLowerCase().trim();
        } catch (Exception malformedOrUnreadableBody) {
            // Falls back to IP-only limiting for this request; the controller's own @Valid
            // validation will reject a genuinely malformed login body anyway.
            return null;
        }
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request, RateLimitResult result) throws IOException {
        long retryAfterSeconds = Math.max(1, Math.ceilDiv(result.retryAfter().toMillis(), 1000));
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Remaining", "0");
        writeError(response, request, 429, "Too Many Requests",
                "Rate limit exceeded. Please retry later.");
    }

    private void writeServiceUnavailable(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setHeader("Retry-After", "5");
        writeError(response, request, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service Unavailable",
                "Rate limiting is temporarily unavailable. Please retry shortly.");
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .status(status)
                .error(error)
                .message(message)
                .path(request.getRequestURI())
                .build();
        objectMapper.writeValue(response.getWriter(), body);
    }

    private record Bucket(String key, RateLimitProperties.Policy policy) {
        String category() {
            String[] parts = key.split(":", 3);
            return parts.length >= 2 ? parts[0] + ":" + parts[1] : key;
        }
    }
}
