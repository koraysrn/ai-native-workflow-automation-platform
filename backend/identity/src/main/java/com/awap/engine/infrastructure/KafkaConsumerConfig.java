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
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/** Kafka consumer configuration with retry (exponential backoff) and a DLQ recoverer. */
@Configuration
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

  @Bean
  public ConsumerFactory<String, String> consumerFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
      @Value("${awap.kafka.schema-registry-url:http://localhost:8081}") String schemaRegistryUrl) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    // Schema Registry endpoint for the Avro (de)serializers; see docs/event-schema-evolution.md.
    props.put("schema.registry.url", schemaRegistryUrl);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafkaTemplate) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);

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
