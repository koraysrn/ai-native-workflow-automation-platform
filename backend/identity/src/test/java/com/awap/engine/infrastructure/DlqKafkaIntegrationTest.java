package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-broker verification for issue #123: the dual subscription, the per-topic DLQ tagging, the
 * no-republish guarantee, quarantine of malformed legacy payloads, the large-backlog behaviour and
 * the twice-run idempotency of the copy tool.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DlqKafkaIntegrationTest {

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.0"));

  private static final List<String> TOPICS =
      List.of(
          "awap.dlq",
          "workflow.task.events",
          "workflow.task.events.dlq",
          "workflow.instance.events.dlq",
          "backlog.events.dlq",
          "legacy.copy.source.dlq",
          "not-a-dlq-topic",
          "awap.legacy.dlq");

  private static final String KNOWN_TOPICS =
      "workflow.task.events,workflow.instance.events,analytics.events,backlog.events";

  @TempDir Path tempDir;

  @BeforeAll
  static void createTopics() throws Exception {
    try (Admin admin =
        Admin.create(
            Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(TOPICS.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList())
          .all()
          .get(30, TimeUnit.SECONDS);
    }
  }

  @Test
  @Order(1)
  void consumesLegacyAndPerTopicDeadLettersWithoutRepublishing() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DlqMessageHandler handler = handler(registry, tempDir.resolve("q/first.jsonl"));

    try (RunningListener listener = listener(handler, true)) {
      produce("awap.dlq", "{\"legacy\":true}");
      produce("workflow.task.events.dlq", "{\"task\":true}");
      produce("not-a-dlq-topic", "{\"ignored\":true}");

      await("legacy counter", Duration.ofSeconds(20), () -> received(registry, "awap.dlq") == 1.0);
      await(
          "per-topic counter",
          Duration.ofSeconds(20),
          () -> received(registry, "workflow.task.events.dlq") == 1.0);

      assertThat(legacyCount(registry)).isEqualTo(1.0);

      // Give the pattern subscription a chance to pick up the unrelated topic by mistake.
      Thread.sleep(1_000);
      assertThat(totalReceived(registry)).isEqualTo(2.0);
      assertThat(endOffset("not-a-dlq-topic")).isEqualTo(1L);

      // The handler never publishes: the source topic and other DLQ topics are untouched.
      assertThat(endOffset("workflow.task.events")).isZero();
      assertThat(endOffset("awap.dlq")).isEqualTo(1L);
      assertThat(listener.isRunning()).isTrue();
    }
  }

  @Test
  @Order(2)
  void malformedLegacyMessageIsQuarantinedAndTheConsumerKeepsGoing() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Path quarantine = tempDir.resolve("q/malformed.jsonl");
    DlqMessageHandler handler = handler(registry, quarantine);

    try (RunningListener listener = listener(handler, true)) {
      produce("awap.dlq", "");
      produce("workflow.task.events.dlq", "{\"ok\":true}");

      await("legacy received", Duration.ofSeconds(20), () -> received(registry, "awap.dlq") == 1.0);
      await(
          "per-topic received",
          Duration.ofSeconds(20),
          () -> received(registry, "workflow.task.events.dlq") == 1.0);

      assertThat(quarantined(registry, "awap.dlq")).isEqualTo(1.0);
      assertThat(legacyCount(registry)).isEqualTo(1.0);
      assertThat(listener.isRunning()).isTrue();

      List<String> lines = Files.readAllLines(quarantine, StandardCharsets.UTF_8);
      assertThat(lines).hasSize(1);
      assertThat(lines.get(0)).contains("blank-payload");
    }
  }

  @Test
  @Order(3)
  void largeBacklogIsDrainedWithoutBlockingTheListener() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DlqMessageHandler handler = handler(registry, tempDir.resolve("q/backlog.jsonl"));

    try (RunningListener listener = listener(handler, true)) {
      produceBatch("backlog.events.dlq", 500);

      await(
          "500 backlog messages",
          Duration.ofSeconds(45),
          () -> received(registry, "backlog.events.dlq") == 500.0);

      assertThat(endOffset("backlog.events.dlq")).isEqualTo(500L);
      assertThat(listener.isRunning()).isTrue();
    }
  }

  @Test
  @Order(4)
  void copyToolCopiesTheLegacyMessageExactlyOnceAcrossTwoRuns() {
    String source = "legacy.copy.source.dlq";
    long destinationBefore = endOffset("workflow.task.events.dlq");
    long sourceBefore = endOffset(source);
    produce(source, "{\"copy\":true}", "kafka_dlt-original-topic", "workflow.task.events");
    Path stateFile = tempDir.resolve("copy-state.properties");

    LegacyDlqCopyService.Result dryRun = runCopy(source, stateFile, true);
    assertThat(dryRun.copied()).isEqualTo(1);
    assertThat(endOffset("workflow.task.events.dlq")).isEqualTo(destinationBefore);
    assertThat(stateFile).doesNotExist();

    LegacyDlqCopyService.Result first = runCopy(source, stateFile, false);
    assertThat(first.copied()).isEqualTo(1);
    assertThat(first.skipped()).isZero();

    LegacyDlqCopyService.Result second = runCopy(source, stateFile, false);
    assertThat(second.copied()).isZero();
    assertThat(second.skipped()).isEqualTo(1);

    assertThat(endOffset("workflow.task.events.dlq")).isEqualTo(destinationBefore + 1);
    assertThat(endOffset(source)).isEqualTo(sourceBefore + 1);

    List<ConsumerRecord<String, String>> copied =
        readAll("workflow.task.events.dlq").stream()
            .filter(record -> "{\"copy\":true}".equals(record.value()))
            .toList();
    assertThat(copied).hasSize(1);
    assertThat(header(copied.get(0), "awap-dlq-copied-from")).isEqualTo(source);
    assertThat(header(copied.get(0), "kafka_dlt-original-topic")).isEqualTo("workflow.task.events");
  }

  @Test
  @Order(5)
  void disablingTheFlagStopsLegacyConsumptionButThePerTopicPathStillWorks() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DlqMessageHandler handler = handler(registry, tempDir.resolve("q/flag-off.jsonl"));

    try (RunningListener listener = listener(handler, false)) {
      produce("awap.dlq", "{\"legacy\":true}");
      produce("workflow.instance.events.dlq", "{\"instance\":true}");

      await(
          "per-topic counter with flag off",
          Duration.ofSeconds(20),
          () -> received(registry, "workflow.instance.events.dlq") == 1.0);

      Thread.sleep(1_000);
      assertThat(received(registry, "awap.dlq")).isZero();
      assertThat(legacyCount(registry)).isZero();
    }
  }

  private DlqMessageHandler handler(MeterRegistry registry, Path quarantine) {
    return new DlqMessageHandler(
        new DlqMetrics(registry, KNOWN_TOPICS),
        new DlqMessageInspector(new ObjectMapper()),
        new DlqQuarantineStore(quarantine),
        new DlqMessageDeduplicator());
  }

  private RunningListener listener(DlqMessageHandler handler, boolean legacyEnabled) {
    DlqTopicSubscription subscription =
        new DlqTopicSubscription(
            legacyEnabled, DlqTopics.LEGACY_TOPIC, DlqTopics.PER_TOPIC_PATTERN);

    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1_000);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
    ConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(props);

    ContainerProperties containerProperties =
        new ContainerProperties(Pattern.compile(subscription.topicPattern()));
    containerProperties.setGroupId("awap-dlq-it-" + UUID.randomUUID());
    containerProperties.setPollTimeout(200);
    containerProperties.setMessageListener((MessageListener<String, String>) handler::handle);

    ConcurrentMessageListenerContainer<String, String> container =
        new ConcurrentMessageListenerContainer<>(consumerFactory, containerProperties);
    container.start();
    await(
        "listener assignment",
        Duration.ofSeconds(20),
        () -> !container.getAssignedPartitions().isEmpty());
    return new RunningListener(container);
  }

  private LegacyDlqCopyService.Result runCopy(String source, Path stateFile, boolean dryRun) {
    try (KafkaConsumer<String, String> consumer = newConsumer();
        KafkaProducer<String, String> producer = newProducer()) {
      return new LegacyDlqCopyService(consumer, producer, new LegacyDlqCopyState(stateFile))
          .run(
              new LegacyDlqCopyService.Options(
                  source, DlqTopics.LEGACY_FALLBACK_TARGET, 100, Duration.ofMillis(200), dryRun));
    }
  }

  private static void produce(String topic, String value) {
    produce(topic, value, null, null);
  }

  private static void produce(String topic, String value, String headerName, String headerValue) {
    try (KafkaProducer<String, String> producer = newProducer()) {
      ProducerRecord<String, String> record = new ProducerRecord<>(topic, value);
      if (headerName != null && headerValue != null) {
        record.headers().add(headerName, headerValue.getBytes(StandardCharsets.UTF_8));
      }
      producer.send(record).get(10, TimeUnit.SECONDS);
      producer.flush();
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to produce a test record to " + topic, ex);
    }
  }

  private static void produceBatch(String topic, int count) {
    try (KafkaProducer<String, String> producer = newProducer()) {
      for (int i = 0; i < count; i++) {
        producer.send(new ProducerRecord<>(topic, "key-" + i, "{\"i\":" + i + "}"));
      }
      producer.flush();
    }
  }

  private static KafkaProducer<String, String> newProducer() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    return new KafkaProducer<>(props);
  }

  private static KafkaConsumer<String, String> newConsumer() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "awap-dlq-it-read-" + UUID.randomUUID());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new KafkaConsumer<>(props);
  }

  private static long endOffset(String topic) {
    try (KafkaConsumer<String, String> consumer = newConsumer()) {
      return endOffset(consumer, topic);
    }
  }

  private static long endOffset(KafkaConsumer<String, String> consumer, String topic) {
    List<TopicPartition> partitions = partitions(consumer, topic);
    return consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
  }

  private static List<ConsumerRecord<String, String>> readAll(String topic) {
    try (KafkaConsumer<String, String> consumer = newConsumer()) {
      List<TopicPartition> partitions = partitions(consumer, topic);
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      List<ConsumerRecord<String, String>> records = new ArrayList<>();
      int emptyPolls = 0;
      while (emptyPolls < 2) {
        ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(300));
        if (batch.isEmpty()) {
          emptyPolls++;
        } else {
          emptyPolls = 0;
          batch.forEach(records::add);
        }
      }
      return records;
    }
  }

  private static List<TopicPartition> partitions(
      KafkaConsumer<String, String> consumer, String topic) {
    return consumer.partitionsFor(topic).stream()
        .map(info -> new TopicPartition(info.topic(), info.partition()))
        .toList();
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    return Optional.ofNullable(record.headers().lastHeader(name))
        .map(Header::value)
        .map(value -> new String(value, StandardCharsets.UTF_8))
        .orElse("");
  }

  private static double received(MeterRegistry registry, String topic) {
    return counter(registry, "awap.dlq.messages", "topic", topic);
  }

  private static double quarantined(MeterRegistry registry, String topic) {
    return counter(registry, "awap.dlq.quarantined", "topic", topic);
  }

  private static double legacyCount(MeterRegistry registry) {
    return counter(registry, "awap.dlq.legacy.messages");
  }

  private static double totalReceived(MeterRegistry registry) {
    return registry.find("awap.dlq.messages").counters().stream().mapToDouble(Counter::count).sum();
  }

  private static double counter(MeterRegistry registry, String name, String... tags) {
    Counter counter = registry.find(name).tags(tags).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static void await(String description, Duration timeout, BooleanSupplier condition) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for " + description, ex);
      }
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  private static final class RunningListener implements AutoCloseable {

    private final ConcurrentMessageListenerContainer<String, String> container;

    private RunningListener(ConcurrentMessageListenerContainer<String, String> container) {
      this.container = container;
    }

    boolean isRunning() {
      return container.isRunning();
    }

    @Override
    public void close() {
      container.stop();
    }
  }
}
