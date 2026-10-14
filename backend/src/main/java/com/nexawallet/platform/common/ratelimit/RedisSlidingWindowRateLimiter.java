package com.nexawallet.platform.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Executes the sliding-window-log Lua script (see {@code scripts/sliding_window_rate_limiter.lua})
 * as a single atomic {@code EVAL} per check — no non-atomic sequence of independent read/write
 * commands that a concurrent request could interleave with.
 *
 * Any failure to reach Redis (timeout, connection refused, etc.) is caught here and reported as
 * {@link RateLimitResult#unavailable()} rather than propagating — callers (the rate-limit
 * filter) decide what that means per their configured fail policy. This method never throws for
 * a Redis-availability reason.
 */
@Service
public class RedisSlidingWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> script;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<List<Long>> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/sliding_window_rate_limiter.lua")));
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<List<Long>> resultType = (Class<List<Long>>) (Class) List.class;
        redisScript.setResultType(resultType);
        this.script = redisScript;
    }

    /**
     * @param key           fully-qualified bucket key, e.g. {@code "transfer:user:<uuid>"}
     * @param limit         max requests allowed per window
     * @param windowSeconds sliding window size, in seconds
     */
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        try {
            long nowMs = System.currentTimeMillis();
            long windowMs = windowSeconds * 1000L;
            String member = nowMs + "-" + UUID.randomUUID();

            List<Long> result = redisTemplate.execute(
                    script,
                    List.of(key),
                    String.valueOf(nowMs), String.valueOf(windowMs), String.valueOf(limit), member);

            if (result == null || result.size() < 3) {
                log.error("Rate limiter script returned an unexpected result for bucket category '{}'", bucketCategory(key));
                return RateLimitResult.unavailable();
            }

            boolean allowed = result.get(0) == 1L;
            long remaining = result.get(1);
            long retryAfterMs = result.get(2);
            return allowed
                    ? RateLimitResult.allowed(remaining)
                    : RateLimitResult.rejected(Duration.ofMillis(retryAfterMs));

        } catch (Exception redisUnavailable) {
            // Never log the raw key (may embed a user id / hashed identifier) alongside a stack
            // trace at ERROR in a way that invites copy-pasting into tickets; the category alone
            // (e.g. "transfer:user") is enough to diagnose which policy is affected.
            log.error("Rate limiter backend unavailable for bucket category '{}': {}",
                    bucketCategory(key), redisUnavailable.getClass().getSimpleName());
            return RateLimitResult.unavailable();
        }
    }

    private String bucketCategory(String key) {
        String[] parts = key.split(":", 3);
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : key;
    }
}
