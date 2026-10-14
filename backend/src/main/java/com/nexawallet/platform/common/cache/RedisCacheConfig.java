package com.nexawallet.platform.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Explicit cache configuration: named caches with their own TTL and a JSON serializer bound to a
 * single concrete DTO type per cache (never {@code GenericJackson2JsonRedisSerializer}'s default
 * typing, which embeds a {@code @class} field and would allow unsafe polymorphic deserialization
 * of whatever that field names).
 *
 * Two {@link CacheManager} implementations are provided, selected by Spring profile:
 * <ul>
 *   <li><b>"test"</b> — {@link ConcurrentMapCacheManager}, a plain in-process map. This is what
 *       lets cache-hit/miss/TTL-adjacent behavior be exercised deterministically in the existing
 *       H2-backed test suite without requiring a live Redis server.</li>
 *   <li><b>everything else (dev/prod)</b> — Spring Boot's auto-configured {@code RedisCacheManager},
 *       customized here via {@link RedisCacheManagerBuilderCustomizer} to attach the "userProfile"
 *       cache's TTL and typed serializer. No manual {@code RedisCacheManager} bean is declared —
 *       Boot's own auto-configuration backs off automatically in the "test" profile because the
 *       {@link ConcurrentMapCacheManager} bean below is already present (standard
 *       {@code @ConditionalOnMissingBean(CacheManager.class)} behavior).</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    public static final String USER_PROFILE_CACHE = "userProfile";

    @Bean
    @Profile("test")
    public CacheManager testCacheManager() {
        return new ConcurrentMapCacheManager(USER_PROFILE_CACHE);
    }

    @Bean
    @Profile("!test")
    public RedisCacheManagerBuilderCustomizer userProfileCacheCustomizer(UserProfileCacheProperties userProfileCacheProperties) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Jackson2JsonRedisSerializer<UserProfileCacheEntry> serializer =
                new Jackson2JsonRedisSerializer<>(mapper, UserProfileCacheEntry.class);

        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(userProfileCacheProperties.getTtlSeconds()))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        return builder -> builder.withCacheConfiguration(USER_PROFILE_CACHE, cacheConfig);
    }
}
