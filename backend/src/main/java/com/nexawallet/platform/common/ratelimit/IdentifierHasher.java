package com.nexawallet.platform.common.ratelimit;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives a non-reversible rate-limit bucket identifier from a normalized value (e.g. an email
 * address) via HMAC-SHA256, so raw emails are never written into Redis keys.
 *
 * Uses a dedicated secret ({@code nexa.ratelimit.identifier-hmac-secret}) rather than reusing the
 * JWT signing key, to avoid using one secret for two unrelated purposes.
 */
@Component
public class IdentifierHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public IdentifierHasher(RateLimitProperties properties) {
        this.key = new SecretKeySpec(properties.getIdentifierHmacSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String normalizedValue) {
        try {
            // A fresh Mac instance per call avoids any question of javax.crypto.Mac thread-safety.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute rate-limit identifier hash", e);
        }
    }
}
