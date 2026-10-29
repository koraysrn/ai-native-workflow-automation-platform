package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyDlqCopyServiceTest {

  private static final String SOURCE = DlqTopics.LEGACY_TOPIC;
  private static final String FALLBACK = DlqTopics.LEGACY_FALLBACK_TARGET;
  private static final Duration POLL_TIMEOUT = Duration.ofMillis(10);

  @TempDir Path tempDir;

  @Test
  void runningTwiceCopiesTheMessageExactlyOnce() {
    Path stateFile = tempDir.resolve("state.properties");

    MockConsumer<String, String> firstConsumer = consumer(record(0, "{\"a\":1}"));
    MockProducer<String, String> firstProducer = producer();
    LegacyDlqCopyService firstService =
        new LegacyDlqCopyService(firstConsumer, firstProducer, new LegacyDlqCopyState(stateFile));

    LegacyDlqCopyService.Result first = firstService.run(options(false, 10));

    assertThat(first.read()).isEqualTo(1);
    assertThat(first.copied()).isEqualTo(1);
    assertThat(first.skipped()).isZero();
    assertThat(firstProducer.history()).hasSize(1);
    assertThat(firstProducer.history().get(0).topic()).isEqualTo(FALLBACK);
    assertThat(firstProducer.history().get(0).value()).isEqualTo("{\"a\":1}");
    assertThat(stateFile).exists();

    MockConsumer<String, String> secondConsumer = consumer(record(0, "{\"a\":1}"));
    MockProducer<String, String> secondProducer = producer();
    LegacyDlqCopyService secondService =
        new LegacyDlqCopyService(secondConsumer, secondProducer, new LegacyDlqCopyState(stateFile));

    LegacyDlqCopyService.Result second = secondService.run(options(false, 10));

    assertThat(second.read()).isEqualTo(1);
    assertThat(second.copied()).isZero();
    assertThat(second.skipped()).isEqualTo(1);
    assertThat(secondProducer.history()).isEmpty();
  }

  @Test
  void dryRunReportsWithoutProducingOrAdvancingTheCheckpoint() {
    Path stateFile = tempDir.resolve("state.properties");
    MockProducer<String, String> producer = producer();
    LegacyDlqCopyService service =
        new LegacyDlqCopyService(
            consumer(record(0, "v")), producer, new LegacyDlqCopyState(stateFile));

    LegacyDlqCopyService.Result result = service.run(options(true, 10));

    assertThat(result.dryRun()).isTrue();
    assertThat(result.copied()).isEqualTo(1);
    assertThat(producer.history()).isEmpty();
    assertThat(stateFile).doesNotExist();

    MockProducer<String, String> realProducer = producer();
    LegacyDlqCopyService realService =
        new LegacyDlqCopyService(
            consumer(record(0, "v")), realProducer, new LegacyDlqCopyState(stateFile));
    assertThat(realService.run(options(false, 10)).copied()).isEqualTo(1);
    assertThat(realProducer.history()).hasSize(1);
  }

  @Test
  void maxMessagesBoundsEachRunAndTheNextRunContinues() {
    Path stateFile = tempDir.resolve("state.properties");
    MockProducer<String, String> firstProducer = producer();
    LegacyDlqCopyService firstService =
        new LegacyDlqCopyService(
            consumer(record(0, "one"), record(1, "two")),
            firstProducer,
            new LegacyDlqCopyState(stateFile));

    LegacyDlqCopyService.Result first = firstService.run(options(false, 1));
    assertThat(first.copied()).isEqualTo(1);
    assertThat(firstProducer.history()).hasSize(1);

    MockProducer<String, String> secondProducer = producer();
    LegacyDlqCopyService secondService =
        new LegacyDlqCopyService(
            consumer(record(0, "one"), record(1, "two")),
            secondProducer,
            new LegacyDlqCopyState(stateFile));
    LegacyDlqCopyService.Result second = secondService.run(options(false, 1));
    assertThat(second.skipped()).isEqualTo(1);
    assertThat(second.copied()).isEqualTo(1);
    assertThat(secondProducer.history()).hasSize(1);
    assertThat(secondProducer.history().get(0).value()).isEqualTo("two");

    MockProducer<String, String> thirdProducer = producer();
    LegacyDlqCopyService thirdService =
        new LegacyDlqCopyService(
            consumer(record(0, "one"), record(1, "two")),
            thirdProducer,
            new LegacyDlqCopyState(stateFile));
    LegacyDlqCopyService.Result third = thirdService.run(options(false, 10));
    assertThat(third.copied()).isZero();
    assertThat(third.skipped()).isEqualTo(2);
    assertThat(thirdProducer.history()).isEmpty();
  }

  @Test
  void originalTopicHeaderSelectsThePerTopicDlqAndIsPreservedAsAuditHeader() {
    ConsumerRecord<String, String> source = record(0, "{\"event\":\"x\"}");
    source
        .headers()
        .add("kafka_dlt-original-topic", "analytics.events".getBytes(StandardCharsets.UTF_8));
    MockProducer<String, String> producer = producer();
    LegacyDlqCopyService service =
        new LegacyDlqCopyService(
            consumer(source),
            producer,
            new LegacyDlqCopyState(tempDir.resolve("state.properties")));

    service.run(options(false, 10));

    ProducerRecord<String, String> copied = producer.history().get(0);
    assertThat(copied.topic()).isEqualTo("analytics.events.dlq");
    assertThat(header(copied, "awap-dlq-copied-from")).isEqualTo(SOURCE);
    assertThat(header(copied, "awap-dlq-source-offset")).isEqualTo("0");
  }

  @Test
  void unsafeOriginalTopicFallsBackInsteadOfWritingToTheSourceTopic() {
    ConsumerRecord<String, String> source = record(0, "v");
    source.headers().add("kafka_dlt-original-topic", "awap".getBytes(StandardCharsets.UTF_8));
    MockProducer<String, String> producer = producer();
    LegacyDlqCopyService service =
        new LegacyDlqCopyService(
            consumer(source),
            producer,
            new LegacyDlqCopyState(tempDir.resolve("state.properties")));

    service.run(options(false, 10));

    assertThat(producer.history()).hasSize(1);
    assertThat(producer.history().get(0).topic()).isEqualTo(FALLBACK);
  }

  @Test
  void emptySourceTopicIsHandledWithoutProducing() {
    MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    consumer.updatePartitions(SOURCE, List.of());
    MockProducer<String, String> producer = producer();
    LegacyDlqCopyService service =
        new LegacyDlqCopyService(
            consumer, producer, new LegacyDlqCopyState(tempDir.resolve("state.properties")));

    LegacyDlqCopyService.Result result = service.run(options(false, 10));

    assertThat(result.read()).isZero();
    assertThat(result.copied()).isZero();
    assertThat(producer.history()).isEmpty();
  }

  @Test
  void failedFlushDoesNotAdvanceTheCheckpointSoNothingIsLost() {
    Path stateFile = tempDir.resolve("state.properties");
    @SuppressWarnings("unchecked")
    Producer<String, String> failingProducer = mock(Producer.class);
    doReturn(mock(Future.class)).when(failingProducer).send(any());
    doThrow(new KafkaException("broker down")).when(failingProducer).flush();

    LegacyDlqCopyService failingService =
        new LegacyDlqCopyService(
            consumer(record(0, "v")), failingProducer, new LegacyDlqCopyState(stateFile));

    assertThatThrownBy(() -> failingService.run(options(false, 10)))
        .isInstanceOf(KafkaException.class)
        .hasMessageContaining("broker down");
    assertThat(stateFile).doesNotExist();

    MockProducer<String, String> retryProducer = producer();
    LegacyDlqCopyService retryService =
        new LegacyDlqCopyService(
            consumer(record(0, "v")), retryProducer, new LegacyDlqCopyState(stateFile));
    assertThat(retryService.run(options(false, 10)).copied()).isEqualTo(1);
    assertThat(retryProducer.history()).hasSize(1);
  }

  @Test
  void invalidOptionsAreRejected() {
    assertThatThrownBy(
            () -> new LegacyDlqCopyService.Options(SOURCE, SOURCE, 10, POLL_TIMEOUT, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fallbackTargetTopic");
    assertThatThrownBy(
            () -> new LegacyDlqCopyService.Options(" ", FALLBACK, 10, POLL_TIMEOUT, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceTopic");
    assertThatThrownBy(
            () -> new LegacyDlqCopyService.Options(SOURCE, FALLBACK, 0, POLL_TIMEOUT, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxMessages");
  }

  private LegacyDlqCopyService.Options options(boolean dryRun, int maxMessages) {
    return new LegacyDlqCopyService.Options(SOURCE, FALLBACK, maxMessages, POLL_TIMEOUT, dryRun);
  }

  private static String header(ProducerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static ConsumerRecord<String, String> record(long offset, String value) {
    return new ConsumerRecord<>(SOURCE, 0, offset, "key-" + offset, value);
  }

  private static MockConsumer<String, String> consumer(ConsumerRecord<String, String>... records) {
    MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    TopicPartition partition = new TopicPartition(SOURCE, 0);
    consumer.updatePartitions(
        SOURCE, List.of(new PartitionInfo(SOURCE, 0, Node.noNode(), new Node[0], new Node[0])));
    consumer.updateBeginningOffsets(Map.of(partition, 0L));
    consumer.assign(List.of(partition));
    for (ConsumerRecord<String, String> record : records) {
      consumer.addRecord(record);
    }
    return consumer;
  }

  private static MockProducer<String, String> producer() {
    return new MockProducer<>(
        true,
        new org.apache.kafka.clients.producer.RoundRobinPartitioner(),
        new StringSerializer(),
        new StringSerializer());
  }
}
