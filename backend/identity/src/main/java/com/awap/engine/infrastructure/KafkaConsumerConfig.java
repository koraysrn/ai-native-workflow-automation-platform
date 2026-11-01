package com.awap.engine.infrastructure;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/** Kafka consumer configuration with retry (exponential backoff) and a DLQ recoverer. */
@Configuration
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

  @Bean
  public ConsumerFactory<String, String> consumerFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
      @Value("${awap.kafka.schema-registry-url:http://localhost:8081}") String schemaRegistryUrl,
      @Value("${awap.kafka.max-poll-records:50}") int maxPollRecords,
      @Value("${awap.kafka.consumer-group-id:awap-consumer}") String groupId) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    // Every @KafkaListener needs a group id. Listeners on distinct topics share this default;
    // listeners that share a topic override it so they do not compete for partitions.
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    // Schema Registry endpoint for the Avro (de)serializers; see docs/event-schema-evolution.md.
    props.put("schema.registry.url", schemaRegistryUrl);
    configureDeadLetterConsumption(props, maxPollRecords);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  /**
   * Consumer-side resilience shared by every listener (issue #123).
   *
   * <p>{@code earliest} makes a restarted consumer pick up the existing DLQ backlog instead of
   * silently skipping it. Reconnect/retry backoff keeps the listeners alive while Kafka is
   * unavailable at startup: the container starts, retries in the background and recovers once the
   * broker is reachable. The bounded poll/fetch sizes keep a large backlog from monopolising the
   * consumer thread.
   */
  static void configureDeadLetterConsumption(Map<String, Object> props, int maxPollRecords) {
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Math.max(1, maxPollRecords));
    props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 5 * 1024 * 1024);
    props.put(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1_000);
    props.put(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 30_000);
    props.put(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, 1_000);
    props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 5_000);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafkaTemplate) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);

    // Commit the offset only after the listener returns, i.e. after the notification delivery row
    // (or its retry schedule) is durable. A DB outage throws out of the listener and the record is
    // re-delivered instead of being silently lost.
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.getContainerProperties().setAsyncAcks(false);

    // Per-topic DLQ: every consumed topic gets its own "<topic>.dlq" so a poison record in
    // analytics cannot hide behind workflow failures and vice versa.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) ->
                new org.apache.kafka.common.TopicPartition(
                    record.topic() + ".dlq", record.partition()));
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new JitteredBackOff());
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}
