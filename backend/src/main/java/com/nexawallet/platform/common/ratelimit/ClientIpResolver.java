package com.nexawallet.platform.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves the "real" client IP for rate-limit bucketing.
 *
 * {@code X-Forwarded-For} is attacker-controlled on any request that reaches this application
 * directly from the public internet — trusting it unconditionally would let anyone bypass IP-based
 * rate limiting by sending a different value on every request. It is honored only when the
 * socket's immediate peer address ({@link HttpServletRequest#getRemoteAddr()}) matches one of the
 * operator-configured {@code nexa.ratelimit.trusted-proxies} entries (exact IP or CIDR); otherwise
 * the raw socket address is used, always. The default is an empty trust list, so by default the
 * header is never honored.
 */
@Component
public class ClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final List<IpAddressMatcher> trustedProxyMatchers;

    public ClientIpResolver(RateLimitProperties properties) {
        this.trustedProxyMatchers = parse(properties.getTrustedProxies());
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null) {
            return "unknown";
        }
        if (isTrustedProxy(remoteAddr)) {
            String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
            if (StringUtils.hasText(forwardedFor)) {
                String first = forwardedFor.split(",")[0].trim();
                if (StringUtils.hasText(first)) {
                    return first;
                }
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        for (IpAddressMatcher matcher : trustedProxyMatchers) {
            try {
                if (matcher.matches(remoteAddr)) {
                    return true;
                }
            } catch (IllegalArgumentException malformedRemoteAddr) {
                return false;
            }
        }
        return false;
    }

    private static List<IpAddressMatcher> parse(String trustedProxiesCsv) {
        if (!StringUtils.hasText(trustedProxiesCsv)) {
            return List.of();
        }
        return Arrays.stream(trustedProxiesCsv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(IpAddressMatcher::new)
                .toList();
    }
}
