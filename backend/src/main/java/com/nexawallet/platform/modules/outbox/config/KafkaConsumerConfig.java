package com.nexawallet.platform.modules.outbox.config;

import com.nexawallet.platform.modules.outbox.exception.NonRetryableEventException;
import com.nexawallet.platform.modules.outbox.kafka.KafkaTopics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Explicit String/String consumer configuration with manual acknowledgement and a
 * bounded-retry-then-dead-letter error handler.
 *
 * Retry/DLQ policy: a transient failure (DB hiccup while recording the
 * processed-event marker, etc.) is retried with exponential backoff up to
 * {@code nexa.kafka.consumer.max-retries} times; a {@link NonRetryableEventException}
 * (malformed payload, unsupported schema version) skips straight to the dead-letter
 * topic. Either way, {@link DeadLetterPublishingRecoverer} forwards the message to
 * {@link KafkaTopics#DLQ} with headers preserving the original topic, partition,
 * offset, and exception details — the original event id is already inside the
 * (unmodified) JSON payload that gets forwarded along with it.
 */
@Configuration
public class KafkaConsumerConfig {

    private final KafkaProperties kafkaProperties;

    public KafkaConsumerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(kafkaProperties.buildConsumerProperties(null));
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<String, String> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.DLQ, -1 /* let the broker partitioner choose */));
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer,
            @Value("${nexa.kafka.consumer.max-retries:5}") int maxRetries,
            @Value("${nexa.kafka.consumer.retry-backoff-ms:500}") long retryBackoffMs
    ) {
        ExponentialBackOff backOff = new ExponentialBackOff(retryBackoffMs, 2.0);
        backOff.setMaxAttempts(maxRetries);

        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetterPublishingRecoverer, backOff);
        // Malformed/unsupported-schema messages can never succeed on retry — send straight to the DLQ.
        handler.addNotRetryableExceptions(NonRetryableEventException.class);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
