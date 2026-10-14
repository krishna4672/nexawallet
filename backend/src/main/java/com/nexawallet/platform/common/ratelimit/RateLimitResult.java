package com.nexawallet.platform.common.ratelimit;

import java.time.Duration;

/**
 * Outcome of a single rate-limit check.
 *
 * {@code backendUnavailable} is a distinct state from "allowed" or "rejected" — it means the
 * Redis command itself could not be evaluated (timeout, connection failure), and the caller
 * must apply the configured {@link RateLimitProperties.FailPolicy} rather than silently treating
 * it as either outcome.
 */
public record RateLimitResult(boolean allowed, long remaining, Duration retryAfter, boolean backendUnavailable) {

    public static RateLimitResult allowed(long remaining) {
        return new RateLimitResult(true, remaining, Duration.ZERO, false);
    }

    public static RateLimitResult rejected(Duration retryAfter) {
        return new RateLimitResult(false, 0, retryAfter, false);
    }

    public static RateLimitResult unavailable() {
        return new RateLimitResult(false, 0, Duration.ZERO, true);
    }
}
