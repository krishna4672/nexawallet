package com.nexawallet.platform.modules.outbox.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the scheduled outbox publisher. See application.yml ({@code nexa.outbox.publisher.*}).
 */
@Component
@ConfigurationProperties(prefix = "nexa.outbox.publisher")
@Getter
@Setter
public class OutboxPublisherProperties {

    /** Master on/off switch — disabled in the test profile so unrelated tests never touch Kafka. */
    private boolean enabled = true;

    /** Max rows claimed per poll cycle. */
    private int batchSize = 50;

    /** Delay between poll cycles, in milliseconds. */
    private long pollIntervalMs = 2000;

    /** How long to wait for a broker acknowledgement per event before treating it as a failure. */
    private long sendTimeoutMs = 10_000;

    /** Number of retry attempts allowed before an event is moved to FAILED. */
    private int maxRetries = 8;

    /** Base delay for exponential backoff between retries, in milliseconds. */
    private long baseBackoffMs = 1000;

    /** Upper bound for exponential backoff, in milliseconds. */
    private long maxBackoffMs = 60_000;
}
