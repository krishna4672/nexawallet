package com.nexawallet.platform.common.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TTL for the {@code userProfile} Redis cache (see {@code nexa.cache.user-profile.*}).
 * Only affects freshness of name/email/role/createdAt — wallet balance is never cached and is
 * unaffected by this value.
 */
@Component
@ConfigurationProperties(prefix = "nexa.cache.user-profile")
@Getter
@Setter
public class UserProfileCacheProperties {
    private long ttlSeconds = 600;
}
