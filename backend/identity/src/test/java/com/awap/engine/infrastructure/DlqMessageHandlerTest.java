package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DlqMessageHandlerTest {

  @TempDir Path tempDir;

  private SimpleMeterRegistry registry;
  private DlqMessageHandler handler;
  private Path quarantinePath;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    quarantinePath = tempDir.resolve("quarantine/legacy-dlq.jsonl");
    handler =
        new DlqMessageHandler(
            new DlqMetrics(registry, "workflow.task.events,analytics.events"),
            new DlqMessageInspector(new ObjectMapper()),
            new DlqQuarantineStore(quarantinePath),
            new DlqMessageDeduplicator());
  }

  @Test
  void validPerTopicMessageIsCountedOnceAndNotQuarantined() {
    handler.handle(record("workflow.task.events.dlq", 0, 0L, "{\"ok\":true}"));

    assertThat(received("workflow.task.events.dlq")).isEqualTo(1.0);
    assertThat(registry.find("awap.dlq.legacy.messages").counter()).isNull();
    assertThat(Files.exists(quarantinePath)).isFalse();
  }

  @Test
  void redeliveredRecordIsIgnoredByIdempotencyGuard() {
    ConsumerRecord<String, String> record =
        record("workflow.task.events.dlq", 0, 7L, "{\"ok\":true}");

    handler.handle(record);
    handler.handle(record);

    assertThat(received("workflow.task.events.dlq")).isEqualTo(1.0);
    assertThat(duplicates("workflow.task.events.dlq")).isEqualTo(1.0);
  }

  @Test
  void blankLegacyMessageIsQuarantinedAndCountedButDoesNotThrow() {
    handler.handle(record(DlqTopics.LEGACY_TOPIC, 0, 0L, ""));

    assertThat(received(DlqTopics.LEGACY_TOPIC)).isEqualTo(1.0);
    assertThat(registry.find("awap.dlq.legacy.messages").counter().count()).isEqualTo(1.0);
    assertThat(quarantined(DlqTopics.LEGACY_TOPIC)).isEqualTo(1.0);
    assertThat(quarantineLines()).containsExactly("awap.dlq\t0\t0\tblank-payload\t");
  }

  @Test
  void malformedJsonIsQuarantinedWithItsBase64Payload() throws IOException {
    handler.handle(record(DlqTopics.LEGACY_TOPIC, 1, 3L, "{\"broken\":"));

    List<String> lines = quarantineLines();
    assertThat(lines).hasSize(1);
    String[] fields = lines.get(0).split("\t", -1);
    assertThat(fields[0]).isEqualTo(DlqTopics.LEGACY_TOPIC);
    assertThat(fields[1]).isEqualTo("1");
    assertThat(fields[2]).isEqualTo("3");
    assertThat(fields[3]).isEqualTo("invalid-json");
    assertThat(new String(Base64.getDecoder().decode(fields[4]), StandardCharsets.UTF_8))
        .isEqualTo("{\"broken\":");
  }

  @Test
  void consumerKeepsProcessingAfterAMalformedLegacyMessage() {
    handler.handle(record(DlqTopics.LEGACY_TOPIC, 0, 0L, null));
    handler.handle(record(DlqTopics.LEGACY_TOPIC, 0, 1L, "{\"ok\":true}"));

    assertThat(received(DlqTopics.LEGACY_TOPIC)).isEqualTo(2.0);
    assertThat(quarantined(DlqTopics.LEGACY_TOPIC)).isEqualTo(1.0);
    assertThat(quarantineLines()).containsExactly("awap.dlq\t0\t0\tnull-payload\t");
  }

  @Test
  void quarantineFailureIsLoggedAndSwallowedSoTheListenerCannotPoisonLoop() throws IOException {
    Path notADirectory = tempDir.resolve("file-not-a-directory");
    Files.writeString(notADirectory, "x");
    DlqMessageHandler failingHandler =
        new DlqMessageHandler(
            new DlqMetrics(registry, "workflow.task.events"),
            new DlqMessageInspector(new ObjectMapper()),
            new DlqQuarantineStore(notADirectory.resolve("quarantine.jsonl")),
            new DlqMessageDeduplicator());

    assertThatNoException()
        .isThrownBy(() -> failingHandler.handle(record(DlqTopics.LEGACY_TOPIC, 0, 0L, "")));
    assertThat(received(DlqTopics.LEGACY_TOPIC)).isEqualTo(1.0);
  }

  @Test
  void handlerHasNoProducerDependencySoItCannotRepublish() {
    assertThat(Arrays.stream(DlqMessageHandler.class.getDeclaredFields()))
        .noneMatch(
            field ->
                Producer.class.isAssignableFrom(field.getType())
                    || field.getType().getName().contains("KafkaTemplate"));
  }

  private List<String> quarantineLines() {
    try {
      if (!Files.exists(quarantinePath)) {
        return List.of();
      }
      return Files.readAllLines(quarantinePath, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private double received(String topic) {
    return count("awap.dlq.messages", "topic", topic);
  }

  private double quarantined(String topic) {
    return count("awap.dlq.quarantined", "topic", topic);
  }

  private double duplicates(String topic) {
    return count("awap.dlq.duplicates", "topic", topic);
  }

  private double count(String meter, String tagKey, String tagValue) {
    var counter = registry.find(meter).tag(tagKey, tagValue).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static ConsumerRecord<String, String> record(
      String topic, int partition, long offset, String value) {
    return new ConsumerRecord<>(topic, partition, offset, "key-" + offset, value);
  }
}
