package com.nexawallet.platform.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real-Redis tests for the {@code userProfile} cache: genuine TTL expiry (Redis's own wall-clock
 * {@code PEXPIRE}, not a mock), and confirmation that values round-trip as plain JSON (no
 * {@code @class} polymorphic type metadata — see {@link #valuesAreStoredAsPlainJson_noPolymorphicTypeInfo()}).
 * Builds its own {@link RedisCacheManager} replicating {@code RedisCacheConfig}'s non-test-profile
 * wiring, rather than booting a full Spring context, to sidestep the profile-based CacheManager
 * selection entirely for this focused test. {@code disabledWithoutDocker = true} reports every test
 * here SKIPPED (not failed) with no Docker daemon reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("userProfile cache — Real Redis Integration Tests")
class UserProfileCacheRedisIntegrationTest {

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate rawTemplate;

    @BeforeAll
    static void startConnection() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        rawTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopConnection() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    private UserProfileCacheService serviceWithTtl(Duration ttl) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Jackson2JsonRedisSerializer<UserProfileCacheEntry> serializer =
                new Jackson2JsonRedisSerializer<>(mapper, UserProfileCacheEntry.class);

        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        RedisCacheManager cacheManager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .withCacheConfiguration(RedisCacheConfig.USER_PROFILE_CACHE, cacheConfig)
                .build();
        cacheManager.afterPropertiesSet();
        return new UserProfileCacheService(cacheManager);
    }

    private UserProfileCacheEntry entryFor(UUID userId, String email) {
        return UserProfileCacheEntry.builder()
                .id(userId)
                .email(email)
                .firstName("Test")
                .lastName("User")
                .role("ROLE_CUSTOMER")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    @Test
    @DisplayName("1. A cache miss loads via the supplied loader and a subsequent read is served from Redis")
    void cacheMiss_thenHit() {
        UserProfileCacheService service = serviceWithTtl(Duration.ofMinutes(10));
        UUID userId = UUID.randomUUID();
        UserProfileCacheEntry entry = entryFor(userId, "hit-miss@example.com");
        AtomicInteger loadCount = new AtomicInteger();

        UserProfileCacheEntry first = service.getOrLoad(userId, () -> { loadCount.incrementAndGet(); return entry; });
        UserProfileCacheEntry second = service.getOrLoad(userId, () -> { loadCount.incrementAndGet(); return entry; });

        assertThat(first.getEmail()).isEqualTo("hit-miss@example.com");
        assertThat(second.getEmail()).isEqualTo("hit-miss@example.com");
        assertThat(loadCount.get()).isEqualTo(1); // second call was a genuine Redis cache hit
    }

    @Test
    @DisplayName("2. Entries expire after their configured TTL and the loader is invoked again")
    void entryExpiresAfterTtl() {
        UserProfileCacheService service = serviceWithTtl(Duration.ofSeconds(1));
        UUID userId = UUID.randomUUID();
        UserProfileCacheEntry entry = entryFor(userId, "ttl@example.com");
        AtomicInteger loadCount = new AtomicInteger();

        service.getOrLoad(userId, () -> { loadCount.incrementAndGet(); return entry; });
        assertThat(loadCount.get()).isEqualTo(1);

        // Poll until Redis's own TTL has genuinely elapsed, rather than a blind fixed sleep.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            service.getOrLoad(userId, () -> { loadCount.incrementAndGet(); return entry; });
            assertThat(loadCount.get()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("3. evict() removes the entry immediately, before TTL — the next read is a fresh load")
    void evict_removesEntryImmediately() {
        UserProfileCacheService service = serviceWithTtl(Duration.ofMinutes(10));
        UUID userId = UUID.randomUUID();
        UserProfileCacheEntry entry = entryFor(userId, "evict@example.com");
        AtomicInteger loadCount = new AtomicInteger();

        service.getOrLoad(userId, () -> { loadCount.incrementAndGet(); return entry; });
        service.evict(userId);
        service.getOrLoad(userId, () -> { loadCount.incrementAndGet(); return entry; });

        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("4. Two different users' cached entries are isolated and never cross-returned")
    void differentUsers_isolated() {
        UserProfileCacheService service = serviceWithTtl(Duration.ofMinutes(10));
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        UserProfileCacheEntry a = service.getOrLoad(userA, () -> entryFor(userA, "userA@example.com"));
        UserProfileCacheEntry b = service.getOrLoad(userB, () -> entryFor(userB, "userB@example.com"));

        assertThat(a.getEmail()).isEqualTo("userA@example.com");
        assertThat(b.getEmail()).isEqualTo("userB@example.com");

        // Re-read both from cache; still isolated
        assertThat(service.getOrLoad(userA, () -> entryFor(userA, "SHOULD-NOT-BE-RETURNED")).getEmail())
                .isEqualTo("userA@example.com");
        assertThat(service.getOrLoad(userB, () -> entryFor(userB, "SHOULD-NOT-BE-RETURNED")).getEmail())
                .isEqualTo("userB@example.com");
    }

    @Test
    @DisplayName("5. Values are stored in Redis as plain JSON with no polymorphic @class type metadata")
    void valuesAreStoredAsPlainJson_noPolymorphicTypeInfo() {
        UserProfileCacheService service = serviceWithTtl(Duration.ofMinutes(10));
        UUID userId = UUID.randomUUID();
        service.getOrLoad(userId, () -> entryFor(userId, "json-shape@example.com"));

        String rawValue = rawTemplate.opsForValue().get(RedisCacheConfig.USER_PROFILE_CACHE + "::" + userId);

        assertThat(rawValue).isNotNull();
        assertThat(rawValue).doesNotContain("@class");
        assertThat(rawValue).contains("\"email\":\"json-shape@example.com\"");
    }
}
