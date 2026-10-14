package com.nexawallet.platform.common.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for Redis-backed distributed sliding-window rate limiting
 * (see {@code nexa.ratelimit.*} in application.yml / .env.example).
 */
@Component
@ConfigurationProperties(prefix = "nexa.ratelimit")
@Getter
@Setter
public class RateLimitProperties {

    /** Master on/off switch — disabled in the test profile so unrelated tests never require Redis. */
    private boolean enabled = true;

    /**
     * What to do when the Redis backend cannot be reached to evaluate a limit.
     * CLOSED (default, recommended for auth/money endpoints): reject with 503 rather than risk
     * unlimited throughput. OPEN: let the request through — only use this with a deliberate,
     * documented trade-off, never as a silent fallback.
     */
    private FailPolicy failPolicy = FailPolicy.CLOSED;

    /** Comma-separated trusted reverse-proxy IPs/CIDRs allowed to set X-Forwarded-For. Empty = never trust it. */
    private String trustedProxies = "";

    /** HMAC key used only to derive a non-reversible per-identifier bucket for login attempts. */
    private String identifierHmacSecret = "change-me-rate-limit-hmac-secret-dev-only-0123456789abcdef";

    private Policy loginIp = new Policy(10, 60);
    private Policy loginIdentifier = new Policy(5, 60);
    private Policy registerIp = new Policy(5, 3600);
    private Policy transferUser = new Policy(20, 60);
    private Policy transferIp = new Policy(60, 60);
    private Policy depositUser = new Policy(20, 60);
    private Policy depositIp = new Policy(60, 60);

    public enum FailPolicy {
        CLOSED, OPEN
    }

    @Getter
    @Setter
    public static class Policy {
        private int limit;
        private int windowSeconds;

        public Policy() {
        }

        public Policy(int limit, int windowSeconds) {
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }
    }
}
