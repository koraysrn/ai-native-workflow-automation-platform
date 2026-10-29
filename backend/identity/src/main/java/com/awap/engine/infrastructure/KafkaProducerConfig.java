package com.awap.engine.infrastructure;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Idempotent Kafka producer used by the outbox relay (issue #121, design item 6).
 *
 * <p>Spring Boot 4 no longer ships a Kafka auto-configuration module with this project's dependency
 * set (only {@code spring-kafka} is declared), so the producer was never created and the relay was
 * silently inert. The producer is defined explicitly here and pinned to the settings the outbox
 * contract depends on:
 *
 * <ul>
 *   <li>{@code enable.idempotence=true} + {@code acks=all}: a republish after a relay crash cannot
 *       duplicate the record and a send only succeeds once every in-sync replica has it,
 *   <li>{@code max.in.flight.requests.per.connection=5} (the maximum allowed with idempotence) and
 *       unbounded retries so transient broker errors do not surface as fake failures,
 *   <li>the record key is the aggregate id (set by the relay), keeping per-aggregate ordering.
 * </ul>
 *
 * <p>All values come from {@code spring.kafka.producer.*} so a future Boot Kafka module would bind
 * exactly the same configuration.
 */
@Configuration
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class KafkaProducerConfig {

  private final String bootstrapServers;
  private final String acks;
  private final boolean idempotence;
  private final int maxInFlightRequestsPerConnection;
  private final int retries;
  private final int deliveryTimeoutMs;

  public KafkaProducerConfig(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
      @Value("${spring.kafka.producer.acks:all}") String acks,
      @Value("${spring.kafka.producer.properties.enable.idempotence:true}") boolean idempotence,
      @Value("${spring.kafka.producer.properties.max.in.flight.requests.per.connection:5}")
          int maxInFlightRequestsPerConnection,
      @Value("${spring.kafka.producer.retries:2147483647}") int retries,
      @Value("${spring.kafka.producer.properties.delivery.timeout.ms:30000}")
          int deliveryTimeoutMs) {
    this.bootstrapServers = bootstrapServers;
    this.acks = acks;
    this.idempotence = idempotence;
    this.maxInFlightRequestsPerConnection = maxInFlightRequestsPerConnection;
    this.retries = retries;
    this.deliveryTimeoutMs = deliveryTimeoutMs;
  }

  @Bean
  @ConditionalOnMissingBean(ProducerFactory.class)
  public ProducerFactory<String, String> outboxProducerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, acks);
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, idempotence);
    props.put(
        ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, maxInFlightRequestsPerConnection);
    props.put(ProducerConfig.RETRIES_CONFIG, retries);
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  @ConditionalOnMissingBean(KafkaTemplate.class)
  public KafkaTemplate<String, String> kafkaTemplate(
      ProducerFactory<String, String> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }
}
