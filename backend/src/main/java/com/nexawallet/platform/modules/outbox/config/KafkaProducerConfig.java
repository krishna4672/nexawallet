package com.nexawallet.platform.modules.outbox.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Explicit String/String producer configuration, built from the standard
 * {@code spring.kafka.producer.*} properties (see application.yml) plus
 * idempotence settings so retried sends can never create duplicate messages
 * on the broker side of a single publish attempt.
 *
 * Declared explicitly (rather than relying on Spring Boot's auto-configured
 * {@code KafkaTemplate<Object, Object>}) so the outbox publisher can depend on
 * a strictly-typed {@code KafkaTemplate<String, String>}.
 */
@Configuration
public class KafkaProducerConfig {

    private final KafkaProperties kafkaProperties;

    public KafkaProducerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        var props = kafkaProperties.buildProducerProperties(null);
        // Idempotent producer: safe to retry a send without risking broker-side duplicates
        // within that single attempt (this is independent of — and narrower than — the
        // outbox's own at-least-once retry semantics across process restarts).
        props.putIfAbsent(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        props.putIfAbsent(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
