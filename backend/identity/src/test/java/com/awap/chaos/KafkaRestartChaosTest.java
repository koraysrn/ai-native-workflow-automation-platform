package com.awap.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Kafka outage and duplicate-delivery fault injection (issue #211): a committed event survives a
 * broker restart, and the consumer-side idempotency key suppresses a duplicate delivery.
 */
@Tag("chaos")
@Testcontainers(disabledWithoutDocker = true)
class KafkaRestartChaosTest {

  private static final String TOPIC = "chaos.events";

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.0"));

  private static Map<String, Object> producerProps() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    return props;
  }

  private static Map<String, Object> boundedProducerProps() {
    Map<String, Object> props = producerProps();
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1_500);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000);
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_000);
    props.put(ProducerConfig.RETRIES_CONFIG, 1);
    return props;
  }

  private static KafkaConsumer<String, String> consumer() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "chaos-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new KafkaConsumer<>(props);
  }

  @Test
  void committedEventsSurviveABrokerRestartAndDuplicatesAreProcessedOnce() throws Exception {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
      producer
          .send(new ProducerRecord<>(TOPIC, "same-key", "{\"event\":1}"))
          .get(30, TimeUnit.SECONDS);
      // A redelivery of the same business event before the outage.
      producer
          .send(new ProducerRecord<>(TOPIC, "same-key", "{\"event\":1}"))
          .get(30, TimeUnit.SECONDS);
    }

    // A broker outage freezes the broker process without discarding its log directory. A produce
    // during the outage must fail in bounded time instead of blocking the request thread.
    String containerId = KAFKA.getContainerId();
    KAFKA.getDockerClient().pauseContainerCmd(containerId).exec();
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(boundedProducerProps())) {
      assertThatThrownBy(
              () ->
                  producer
                      .send(new ProducerRecord<>(TOPIC, "during-outage", "{\"event\":2}"))
                      .get(5, TimeUnit.SECONDS))
          .isInstanceOf(Exception.class);
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(containerId).exec();
    }

    List<String> received = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(TOPIC));
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (System.nanoTime() < deadline && received.size() < 2) {
        consumer.poll(Duration.ofMillis(500)).forEach(record -> received.add(record.key()));
      }
    }

    assertThat(received).hasSize(2);
    Set<String> uniqueBusinessKeys = new HashSet<>(received);
    assertThat(uniqueBusinessKeys).containsExactly("same-key");
  }
}
