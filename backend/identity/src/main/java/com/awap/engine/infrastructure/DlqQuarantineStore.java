package com.awap.engine.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Append-only quarantine for malformed dead-letter payloads.
 *
 * <p>Quarantining is the safe "dead-letter location" for a record the handler cannot inspect. The
 * payload is Base64 encoded and written to an operator-controlled file instead of being logged, so
 * no personal data reaches the log pipeline. The listener never re-publishes to Kafka.
 */
@Component
public class DlqQuarantineStore {

  private final Path path;

  @Autowired
  public DlqQuarantineStore(
      @Value(
              "${awap.kafka.dlq-quarantine-path:"
                  + "target/dlq-quarantine/legacy-dlq-quarantine.jsonl}")
          String path) {
    this(Path.of(path));
  }

  public DlqQuarantineStore(Path path) {
    this.path = path;
  }

  public synchronized void store(
      String topic, int partition, long offset, String payload, String reason) {
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      String encoded =
          Base64.getEncoder()
              .encodeToString((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
      String line =
          String.join(
                  "\t", topic, Integer.toString(partition), Long.toString(offset), reason, encoded)
              + System.lineSeparator();
      Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to append to the DLQ quarantine file " + path, ex);
    }
  }

  public Path path() {
    return path;
  }
}
