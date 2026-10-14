package com.nexawallet.platform.modules.outbox.config;

import com.nexawallet.platform.modules.outbox.kafka.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Provisions the four NexaWallet topics via Spring Kafka's {@code KafkaAdmin}
 * (which creates any missing {@link NewTopic} bean on startup and tolerates the
 * broker being unavailable — it will not fail application startup).
 *
 * A single-broker local/dev cluster cannot honor a replication factor greater
 * than 1; {@code nexa.kafka.topic-replicas} defaults to 1 accordingly. This is a
 * development configuration, not a production-grade highly-available broker setup.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${nexa.kafka.topic-partitions:3}")
    private int partitions;

    @Value("${nexa.kafka.topic-replicas:1}")
    private short replicas;

    @Bean
    public NewTopic walletTransfersTopic() {
        return TopicBuilder.name(KafkaTopics.TRANSFERS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic walletTransactionsTopic() {
        return TopicBuilder.name(KafkaTopics.TRANSACTIONS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic walletNotificationsTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATIONS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic walletDlqTopic() {
        return TopicBuilder.name(KafkaTopics.DLQ).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic servicePointsIndexTopic() {
        return TopicBuilder.name(KafkaTopics.SERVICE_POINTS).partitions(partitions).replicas(replicas).build();
    }
}
