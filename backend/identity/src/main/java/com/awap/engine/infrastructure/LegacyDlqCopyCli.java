package com.awap.engine.infrastructure;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Command-line entry point used by {@code scripts/dlq-legacy-copy.sh}.
 *
 * <p>Defaults target the legacy {@code awap.dlq} topic and the safe fallback destination {@code
 * awap.legacy.dlq}. Every invocation is bounded ({@code --max-messages}) and checkpointed ({@code
 * --state-file}), so the script can be run repeatedly, including with {@code --dry-run}, without
 * duplicating or losing messages. It never writes to the source topic.
 */
public final class LegacyDlqCopyCli {

  static final String DEFAULT_SOURCE_TOPIC = DlqTopics.LEGACY_TOPIC;
  static final String DEFAULT_FALLBACK_TOPIC = DlqTopics.LEGACY_FALLBACK_TARGET;
  static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";
  static final String DEFAULT_STATE_FILE = "target/legacy-dlq-copy-state.properties";
  static final int DEFAULT_MAX_MESSAGES = 10_000;
  static final long DEFAULT_POLL_TIMEOUT_MS = 1_000;

  private LegacyDlqCopyCli() {}

  public static void main(String[] args) {
    int exitCode =
        run(
            args,
            LegacyDlqCopyCli::newConsumer,
            LegacyDlqCopyCli::newProducer,
            System.out,
            System.err);
    System.exit(exitCode);
  }

  public static int run(
      String[] args,
      Function<String, Consumer<String, String>> consumerFactory,
      Function<String, Producer<String, String>> producerFactory,
      PrintStream out,
      PrintStream err) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(consumerFactory, "consumerFactory");
    Objects.requireNonNull(producerFactory, "producerFactory");
    Objects.requireNonNull(out, "out");
    Objects.requireNonNull(err, "err");

    Config config;
    try {
      config = parse(args);
    } catch (IllegalArgumentException ex) {
      err.println("Invalid argument: " + ex.getMessage());
      printUsage(err);
      return 2;
    }

    if (config.help()) {
      printUsage(out);
      return 0;
    }

    LegacyDlqCopyService.Options options =
        new LegacyDlqCopyService.Options(
            config.sourceTopic(),
            config.fallbackTopic(),
            config.maxMessages(),
            Duration.ofMillis(config.pollTimeoutMs()),
            config.dryRun());

    try (Consumer<String, String> consumer = consumerFactory.apply(config.bootstrapServers());
        Producer<String, String> producer = producerFactory.apply(config.bootstrapServers())) {
      LegacyDlqCopyState state = new LegacyDlqCopyState(config.stateFile());
      LegacyDlqCopyService service = new LegacyDlqCopyService(consumer, producer, state);
      LegacyDlqCopyService.Result result = service.run(options);
      out.printf(
          "source=%s dryRun=%s read=%d copied=%d skipped=%d stateFile=%s%n",
          config.sourceTopic(),
          result.dryRun(),
          result.read(),
          result.copied(),
          result.skipped(),
          config.stateFile().toAbsolutePath());
      return 0;
    } catch (RuntimeException ex) {
      err.println("Legacy DLQ copy failed: " + ex.getMessage());
      return 1;
    }
  }

  static Config parse(String[] args) {
    boolean help = false;
    boolean dryRun = false;
    String bootstrapServers =
        System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", DEFAULT_BOOTSTRAP_SERVERS);
    String sourceTopic = DEFAULT_SOURCE_TOPIC;
    String fallbackTopic = DEFAULT_FALLBACK_TOPIC;
    int maxMessages = DEFAULT_MAX_MESSAGES;
    long pollTimeoutMs = DEFAULT_POLL_TIMEOUT_MS;
    Path stateFile =
        Path.of(System.getenv().getOrDefault("AWAP_LEGACY_DLQ_COPY_STATE", DEFAULT_STATE_FILE));

    for (String arg : args) {
      if ("--help".equals(arg) || "-h".equals(arg)) {
        help = true;
      } else if ("--dry-run".equals(arg)) {
        dryRun = true;
      } else if (arg.startsWith("--")) {
        String name;
        String value;
        int separator = arg.indexOf('=');
        if (separator < 0) {
          throw new IllegalArgumentException("expected --name=value but got " + arg);
        }
        name = arg.substring(0, separator);
        value = arg.substring(separator + 1);
        switch (name) {
          case "--bootstrap-servers" -> bootstrapServers = value;
          case "--source-topic" -> sourceTopic = value;
          case "--fallback-topic" -> fallbackTopic = value;
          case "--max-messages" -> maxMessages = parsePositiveInt(value, name);
          case "--poll-timeout-ms" -> pollTimeoutMs = parsePositiveLong(value, name);
          case "--state-file" -> stateFile = Path.of(value);
          default -> throw new IllegalArgumentException("unknown option " + name);
        }
      } else {
        throw new IllegalArgumentException("unexpected argument " + arg);
      }
    }

    return new Config(
        bootstrapServers,
        sourceTopic,
        fallbackTopic,
        maxMessages,
        pollTimeoutMs,
        stateFile,
        dryRun,
        help);
  }

  private static int parsePositiveInt(String value, String name) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed <= 0) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return parsed;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(name + " must be an integer");
    }
  }

  private static long parsePositiveLong(String value, String name) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return parsed;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(name + " must be a long");
    }
  }

  private static void printUsage(PrintStream stream) {
    stream.println(
        """
        Usage: dlq-legacy-copy.sh [--dry-run] [options]

        Copies messages from the legacy awap.dlq topic to per-topic DLQ topics.
        The checkpoint is persisted after the producer flushes, so running the
        command twice copies each message exactly once.

        Options:
          --dry-run                    Report what would be copied, change nothing
          --bootstrap-servers=HOSTS    Kafka bootstrap servers (default: $KAFKA_BOOTSTRAP_SERVERS)
          --source-topic=NAME          Source topic (default: awap.dlq)
          --fallback-topic=NAME        Target when the original topic is unknown
                                       (default: awap.legacy.dlq)
          --max-messages=N             Bound for one invocation (default: 10000)
          --poll-timeout-ms=N          Consumer poll timeout (default: 1000)
          --state-file=PATH            Checkpoint file (default: target/legacy-dlq-copy-state.properties)
          --help, -h                   Print this help

        Deprecation: the legacy listener stays enabled for one release
        (awap.kafka.legacy-dlq-enabled=true). See runbook O9 for the removal steps.
        """);
  }

  private static Consumer<String, String> newConsumer(String bootstrapServers) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "awap-legacy-dlq-copy");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new KafkaConsumer<>(props);
  }

  private static Producer<String, String> newProducer(String bootstrapServers) {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
    return new KafkaProducer<>(props);
  }

  record Config(
      String bootstrapServers,
      String sourceTopic,
      String fallbackTopic,
      int maxMessages,
      long pollTimeoutMs,
      Path stateFile,
      boolean dryRun,
      boolean help) {}
}
