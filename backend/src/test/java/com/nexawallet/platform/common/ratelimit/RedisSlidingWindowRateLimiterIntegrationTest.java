package com.nexawallet.platform.common.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real-Redis tests for the sliding-window Lua script. {@code @Testcontainers(disabledWithoutDocker = true)}
 * reports every test here SKIPPED (not failed) when no Docker daemon is reachable.
 *
 * Deliberately built without a Spring context: two independent {@link RedisSlidingWindowRateLimiter}
 * instances, each with their own connection, both pointed at the same container, is the most direct
 * way to demonstrate that the limit is enforced by Redis itself rather than by any in-process state —
 * i.e. that it would still work correctly shared across two separate application instances.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RedisSlidingWindowRateLimiter — Real Redis Integration Tests")
class RedisSlidingWindowRateLimiterIntegrationTest {

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactoryA;
    static LettuceConnectionFactory connectionFactoryB;

    @BeforeAll
    static void startConnections() {
        connectionFactoryA = newConnectionFactory();
        connectionFactoryB = newConnectionFactory();
    }

    @AfterAll
    static void stopConnections() {
        if (connectionFactoryA != null) connectionFactoryA.destroy();
        if (connectionFactoryB != null) connectionFactoryB.destroy();
    }

    private static LettuceConnectionFactory newConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisSlidingWindowRateLimiter limiterOn(LettuceConnectionFactory factory) {
        return new RedisSlidingWindowRateLimiter(new StringRedisTemplate(factory));
    }

    @Test
    @DisplayName("1. Requests within the configured limit are all allowed")
    void withinLimit_allAllowed() {
        RedisSlidingWindowRateLimiter limiter = limiterOn(connectionFactoryA);
        String key = "it:within:" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            RateLimitResult result = limiter.tryAcquire(key, 5, 60);
            assertThat(result.allowed()).isTrue();
        }
    }

    @Test
    @DisplayName("2. The (limit+1)th request in the window is rejected with a positive retry-after")
    void exceedingLimit_rejectedWithRetryAfter() {
        RedisSlidingWindowRateLimiter limiter = limiterOn(connectionFactoryA);
        String key = "it:exceed:" + UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(key, 3, 60).allowed()).isTrue();
        }
        RateLimitResult blocked = limiter.tryAcquire(key, 3, 60);

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.backendUnavailable()).isFalse();
        assertThat(blocked.retryAfter()).isPositive();
        assertThat(blocked.retryAfter()).isLessThanOrEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("3. A short window slides and admits new requests again once it elapses")
    void windowSlides_admitsAgainAfterElapsing() {
        RedisSlidingWindowRateLimiter limiter = limiterOn(connectionFactoryA);
        String key = "it:slide:" + UUID.randomUUID();

        assertThat(limiter.tryAcquire(key, 1, 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key, 1, 1).allowed()).isFalse();

        // Poll (rather than a single blind sleep) until the 1-second window has genuinely elapsed —
        // this is real Redis wall-clock TTL, which cannot be driven by an injectable/mock clock.
        await().atMost(Duration.ofSeconds(5)).until(() -> limiter.tryAcquire(key, 1, 1).allowed());
    }

    @Test
    @DisplayName("4. Separate bucket keys (e.g. different users/IPs) never share a quota")
    void separateBuckets_doNotShareQuota() {
        RedisSlidingWindowRateLimiter limiter = limiterOn(connectionFactoryA);
        String keyA = "it:userA:" + UUID.randomUUID();
        String keyB = "it:userB:" + UUID.randomUUID();

        assertThat(limiter.tryAcquire(keyA, 1, 60).allowed()).isTrue();
        assertThat(limiter.tryAcquire(keyA, 1, 60).allowed()).isFalse(); // A exhausted
        assertThat(limiter.tryAcquire(keyB, 1, 60).allowed()).isTrue();  // B is untouched
    }

    @Test
    @DisplayName("5. Concurrent requests against the same key never admit more than the limit (atomicity)")
    void concurrentRequests_neverExceedLimitUnderRace() throws Exception {
        RedisSlidingWindowRateLimiter limiter = limiterOn(connectionFactoryA);
        String key = "it:concurrent:" + UUID.randomUUID();
        int limit = 10;
        int attempts = 50;

        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger allowedCount = new AtomicInteger();
        var futures = IntStream.range(0, attempts)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    if (limiter.tryAcquire(key, limit, 60).allowed()) {
                        allowedCount.incrementAndGet();
                    }
                }, pool))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(allowedCount.get()).isEqualTo(limit);
    }

    @Test
    @DisplayName("6. The limit is enforced across two independent limiter instances sharing the same Redis (simulated multi-instance deployment)")
    void sharedAcrossIndependentInstances_enforcesCombinedLimit() {
        RedisSlidingWindowRateLimiter limiterInstanceA = limiterOn(connectionFactoryA);
        RedisSlidingWindowRateLimiter limiterInstanceB = limiterOn(connectionFactoryB);
        String key = "it:shared:" + UUID.randomUUID();
        int limit = 4;

        // Alternate calls between the two "application instances" — if the limit were tracked
        // in-process rather than in Redis, each instance would separately allow its own 4 requests
        // (8 total). Because the state lives in Redis, the combined total across both is capped at 4.
        int allowed = 0;
        for (int i = 0; i < 8; i++) {
            RedisSlidingWindowRateLimiter limiter = (i % 2 == 0) ? limiterInstanceA : limiterInstanceB;
            if (limiter.tryAcquire(key, limit, 60).allowed()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(limit);
    }
}
