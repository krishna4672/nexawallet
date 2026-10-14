package com.nexawallet.platform.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Read-through cache for the non-financial half of a user's {@code /api/v1/auth/me} profile.
 *
 * Deliberately NOT implemented as {@code @Cacheable} — calling the {@link Cache} API directly
 * here means every Redis GET/PUT is wrapped in its own try/catch, so a Redis outage degrades to
 * "always load from the database" instead of breaking the request. This is the concrete mechanism
 * behind the fintech rule that a cache failure must never affect correctness: the wallet balance
 * portion of the response is never touched by this class at all — callers always fetch it live
 * from PostgreSQL and merge it with whatever this class returns.
 *
 * Keys are the user's UUID as a string, so one user's entry can never collide with or be returned
 * for another user's lookup.
 */
@Service
public class UserProfileCacheService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileCacheService.class);

    private final CacheManager cacheManager;

    public UserProfileCacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Returns the cached profile for {@code userId} if present and reachable; otherwise invokes
     * {@code loader}, caches the result (best-effort), and returns it.
     */
    public UserProfileCacheEntry getOrLoad(UUID userId, Supplier<UserProfileCacheEntry> loader) {
        Cache cache = cache();
        if (cache != null) {
            try {
                Cache.ValueWrapper wrapper = cache.get(userId.toString());
                if (wrapper != null) {
                    Object value = wrapper.get();
                    if (value instanceof UserProfileCacheEntry entry) {
                        log.debug("userProfile cache HIT for {}", userId);
                        return entry;
                    }
                }
            } catch (Exception redisUnavailable) {
                log.warn("userProfile cache GET failed ({}); falling back to the database load",
                        redisUnavailable.getClass().getSimpleName());
            }
        }

        UserProfileCacheEntry loaded = loader.get();
        put(userId, loaded);
        return loaded;
    }

    /** Proactively (re)seeds the cache — called on every successful login/registration so a stale entry never outlives a fresh write. */
    public void put(UUID userId, UserProfileCacheEntry entry) {
        Cache cache = cache();
        if (cache == null) {
            return;
        }
        try {
            cache.put(userId.toString(), entry);
        } catch (Exception redisUnavailable) {
            log.warn("userProfile cache PUT failed ({}); the freshly-loaded value was still returned to the caller",
                    redisUnavailable.getClass().getSimpleName());
        }
    }

    public void evict(UUID userId) {
        Cache cache = cache();
        if (cache == null) {
            return;
        }
        try {
            cache.evict(userId.toString());
        } catch (Exception redisUnavailable) {
            log.warn("userProfile cache EVICT failed ({})", redisUnavailable.getClass().getSimpleName());
        }
    }

    private Cache cache() {
        return cacheManager.getCache(RedisCacheConfig.USER_PROFILE_CACHE);
    }
}
