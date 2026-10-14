package com.nexawallet.platform.common.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The only part of a user's profile that is cached: identity fields that essentially never change
 * after registration. Deliberately excludes the password hash, any token/credential material, and
 * — critically — the wallet balance and any other financial state, which must always come straight
 * from PostgreSQL. See {@code UserProfileCacheService} and the README "Phase 5" section.
 *
 * Serialized to/from Redis as plain JSON (see {@code RedisCacheConfig}) — never via Java native
 * object serialization, and never with polymorphic type metadata embedded in the JSON.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileCacheEntry {
    private UUID id;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private OffsetDateTime createdAt;
}
