package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

/** Guards the producer timeout invariant that previously broke every outbox Kafka send. */
class KafkaProducerConfigTest {

  @Test
  void producerTimeoutsSatisfyTheKafkaClientInvariant() {
    KafkaProducerConfig config =
        new KafkaProducerConfig("localhost:9092", "all", true, 5, 3, 10_000, 5_000);

    Map<String, Object> properties = config.outboxProducerFactory().getConfigurationProperties();

    int linger = (int) properties.get(ProducerConfig.LINGER_MS_CONFIG);
    int requestTimeout = (int) properties.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
    int deliveryTimeout = (int) properties.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);

    assertThat(deliveryTimeout).isGreaterThanOrEqualTo(linger + requestTimeout);
    assertThat(properties)
        .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        .containsEntry(ProducerConfig.ACKS_CONFIG, "all");
  }
}
