package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyDlqCopyCliTest {

  @TempDir Path tempDir;

  @Test
  void defaultsTargetTheLegacyTopic() {
    assertThat(LegacyDlqCopyCli.DEFAULT_SOURCE_TOPIC).isEqualTo(DlqTopics.LEGACY_TOPIC);
    assertThat(LegacyDlqCopyCli.DEFAULT_FALLBACK_TOPIC).isEqualTo(DlqTopics.LEGACY_FALLBACK_TARGET);
  }

  @Test
  void parsesTheSupportedOptions() {
    LegacyDlqCopyCli.Config config =
        LegacyDlqCopyCli.parse(
            new String[] {
              "--dry-run",
              "--max-messages=25",
              "--state-file=" + tempDir.resolve("state.properties"),
              "--source-topic=legacy.source.dlq"
            });

    assertThat(config.dryRun()).isTrue();
    assertThat(config.maxMessages()).isEqualTo(25);
    assertThat(config.sourceTopic()).isEqualTo("legacy.source.dlq");
    assertThat(config.stateFile()).isEqualTo(tempDir.resolve("state.properties"));
  }

  @Test
  void rejectsUnknownAndMalformedOptions() {
    assertThatThrownBy(() -> LegacyDlqCopyCli.parse(new String[] {"--unknown=1"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown option");
    assertThatThrownBy(() -> LegacyDlqCopyCli.parse(new String[] {"--max-messages"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--name=value");
    assertThatThrownBy(() -> LegacyDlqCopyCli.parse(new String[] {"--max-messages=0"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void helpExitsZeroWithoutTouchingKafka() {
    @SuppressWarnings("unchecked")
    Function<String, Consumer<String, String>> consumerFactory = mock(Function.class);
    @SuppressWarnings("unchecked")
    Function<String, Producer<String, String>> producerFactory = mock(Function.class);
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exit =
        LegacyDlqCopyCli.run(
            new String[] {"--help"},
            consumerFactory,
            producerFactory,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            System.err);

    assertThat(exit).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("Usage:", "--dry-run");
    verifyNoInteractions(consumerFactory, producerFactory);
  }

  @Test
  void invalidArgumentsReturnExitCodeTwo() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit =
        LegacyDlqCopyCli.run(
            new String[] {"--unknown=1"},
            bootstrap -> mockConsumer(),
            bootstrap -> mockProducer(),
            System.out,
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(exit).isEqualTo(2);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown option");
  }

  @Test
  void copiesAndPrintsTheSummary() {
    var consumer = mockConsumer();
    var producer = mockProducer();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exit =
        LegacyDlqCopyCli.run(
            new String[] {
              "--max-messages=10",
              "--state-file=" + tempDir.resolve("state.properties"),
              "--bootstrap-servers=localhost:9092"
            },
            bootstrap -> consumer,
            bootstrap -> producer,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            System.err);

    assertThat(exit).isZero();
    assertThat(producer.history()).hasSize(1);
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("dryRun=false", "read=1", "copied=1", "skipped=0");
  }

  @Test
  void runtimeFailureReturnsExitCodeOne() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit =
        LegacyDlqCopyCli.run(
            new String[] {"--state-file=" + tempDir.resolve("state.properties")},
            bootstrap -> {
              throw new IllegalStateException("kafka unavailable");
            },
            bootstrap -> mockProducer(),
            System.out,
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(exit).isEqualTo(1);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("kafka unavailable");
  }

  private static org.apache.kafka.clients.consumer.MockConsumer<String, String> mockConsumer() {
    var consumer =
        new org.apache.kafka.clients.consumer.MockConsumer<String, String>(
            org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST);
    var partition = new org.apache.kafka.common.TopicPartition(DlqTopics.LEGACY_TOPIC, 0);
    consumer.updatePartitions(
        DlqTopics.LEGACY_TOPIC,
        java.util.List.of(
            new org.apache.kafka.common.PartitionInfo(
                DlqTopics.LEGACY_TOPIC,
                0,
                org.apache.kafka.common.Node.noNode(),
                new org.apache.kafka.common.Node[0],
                new org.apache.kafka.common.Node[0])));
    consumer.updateBeginningOffsets(java.util.Map.of(partition, 0L));
    consumer.assign(java.util.List.of(partition));
    consumer.addRecord(
        new org.apache.kafka.clients.consumer.ConsumerRecord<>(
            DlqTopics.LEGACY_TOPIC, 0, 0L, "key", "{\"legacy\":true}"));
    return consumer;
  }

  private static org.apache.kafka.clients.producer.MockProducer<String, String> mockProducer() {
    return new org.apache.kafka.clients.producer.MockProducer<>(
        true,
        new org.apache.kafka.clients.producer.RoundRobinPartitioner(),
        new org.apache.kafka.common.serialization.StringSerializer(),
        new org.apache.kafka.common.serialization.StringSerializer());
  }
}
