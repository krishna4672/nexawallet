package com.nexawallet.platform.common.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleValueWrapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the resilient read-through cache wrapper around the Cache API.
 * Uses a mocked {@link CacheManager}/{@link Cache} so Redis-outage behavior can be asserted
 * deterministically (a real connection failure is not needed to prove the try/catch fallback works).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileCacheService — Unit Tests")
class UserProfileCacheServiceTest {

    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    private UUID userId;
    private UserProfileCacheEntry entry;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        entry = UserProfileCacheEntry.builder()
                .id(userId)
                .email("alice@example.com")
                .firstName("Alice")
                .lastName("Hunter")
                .role("ROLE_CUSTOMER")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    @Test
    @DisplayName("1. Cache miss loads via the supplied loader and populates the cache")
    void getOrLoad_cacheMiss_loadsAndPopulates() {
        when(cacheManager.getCache("userProfile")).thenReturn(cache);
        when(cache.get(userId.toString())).thenReturn(null);

        AtomicInteger loadCount = new AtomicInteger();
        UserProfileCacheEntry result = new UserProfileCacheService(cacheManager)
                .getOrLoad(userId, () -> { loadCount.incrementAndGet(); return entry; });

        assertThat(result).isEqualTo(entry);
        assertThat(loadCount.get()).isEqualTo(1);
        verify(cache).put(userId.toString(), entry);
    }

    @Test
    @DisplayName("2. A cache hit never calls the loader")
    void getOrLoad_cacheHit_doesNotInvokeLoader() {
        when(cacheManager.getCache("userProfile")).thenReturn(cache);
        when(cache.get(userId.toString())).thenReturn(new SimpleValueWrapper(entry));

        AtomicInteger loadCount = new AtomicInteger();
        UserProfileCacheEntry result = new UserProfileCacheService(cacheManager)
                .getOrLoad(userId, () -> { loadCount.incrementAndGet(); return entry; });

        assertThat(result).isEqualTo(entry);
        assertThat(loadCount.get()).isZero();
        verify(cache, never()).put(any(), any());
    }

    @Test
    @DisplayName("3. A Redis failure on GET falls back to the loader instead of breaking the call")
    void getOrLoad_cacheGetThrows_fallsBackToLoader() {
        when(cacheManager.getCache("userProfile")).thenReturn(cache);
        when(cache.get(userId.toString())).thenThrow(new RuntimeException("Redis connection refused"));

        UserProfileCacheEntry result = new UserProfileCacheService(cacheManager).getOrLoad(userId, () -> entry);

        assertThat(result).isEqualTo(entry);
    }

    @Test
    @DisplayName("4. A Redis failure on PUT still returns the freshly-loaded value")
    void getOrLoad_cachePutThrows_stillReturnsLoadedValue() {
        when(cacheManager.getCache("userProfile")).thenReturn(cache);
        when(cache.get(userId.toString())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("Redis connection refused"))
                .when(cache).put(anyString(), any());

        UserProfileCacheEntry result = new UserProfileCacheService(cacheManager).getOrLoad(userId, () -> entry);

        assertThat(result).isEqualTo(entry);
    }

    @Test
    @DisplayName("5. A missing CacheManager cache (e.g. misconfiguration) degrades to always-load, never throws")
    void getOrLoad_noCacheRegistered_alwaysLoads() {
        when(cacheManager.getCache("userProfile")).thenReturn(null);

        UserProfileCacheEntry result = new UserProfileCacheService(cacheManager).getOrLoad(userId, () -> entry);

        assertThat(result).isEqualTo(entry);
    }

    @Test
    @DisplayName("6. evict() swallows a Redis failure rather than propagating it")
    void evict_cacheThrows_doesNotPropagate() {
        when(cacheManager.getCache("userProfile")).thenReturn(cache);
        org.mockito.Mockito.doThrow(new RuntimeException("Redis connection refused")).when(cache).evict(anyString());

        new UserProfileCacheService(cacheManager).evict(userId); // must not throw
    }

    @Test
    @DisplayName("7. Two different users' cache keys never collide")
    void differentUsers_haveIsolatedKeys() {
        UUID otherUserId = UUID.randomUUID();
        when(cacheManager.getCache("userProfile")).thenReturn(cache);
        when(cache.get(userId.toString())).thenReturn(new SimpleValueWrapper(entry));
        when(cache.get(otherUserId.toString())).thenReturn(null);

        UserProfileCacheService service = new UserProfileCacheService(cacheManager);
        UserProfileCacheEntry forUserA = service.getOrLoad(userId, () -> entry);
        UserProfileCacheEntry otherEntry = UserProfileCacheEntry.builder().id(otherUserId).email("bob@example.com").build();
        UserProfileCacheEntry forUserB = service.getOrLoad(otherUserId, () -> otherEntry);

        assertThat(forUserA.getId()).isEqualTo(userId);
        assertThat(forUserB.getId()).isEqualTo(otherUserId);
        assertThat(forUserA).isNotEqualTo(forUserB);
    }
}
