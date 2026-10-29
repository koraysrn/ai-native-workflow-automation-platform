package com.awap.engine.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * Durable copy checkpoint for the legacy DLQ tool.
 *
 * <p>One high-water offset per source partition is persisted. A second run therefore skips records
 * that were already copied, even when the tool always reads from the beginning of the topic. The
 * checkpoint is written atomically and only after the Kafka producer flushed its batch, so a crash
 * can duplicate a record (the handler is idempotent) but can never lose one.
 */
public final class LegacyDlqCopyState {

  private final Path path;
  private final Properties offsets = new Properties();

  public LegacyDlqCopyState(Path path) {
    this.path = path;
    load();
  }

  public boolean alreadyCopied(String sourceTopic, int partition, long offset) {
    OptionalLong lastOffset = lastCopiedOffset(sourceTopic, partition);
    return lastOffset.isPresent() && offset <= lastOffset.getAsLong();
  }

  public OptionalLong lastCopiedOffset(String sourceTopic, int partition) {
    String value = offsets.getProperty(key(sourceTopic, partition));
    if (value == null || value.isBlank()) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Long.parseLong(value.trim()));
    } catch (NumberFormatException ex) {
      return OptionalLong.empty();
    }
  }

  public void markCopied(String sourceTopic, int partition, long offset) {
    OptionalLong previous = lastCopiedOffset(sourceTopic, partition);
    if (previous.isPresent() && offset <= previous.getAsLong()) {
      return;
    }
    offsets.setProperty(key(sourceTopic, partition), Long.toString(offset));
  }

  public synchronized void persist() {
    try {
      Path absolute = path.toAbsolutePath();
      Path parent = absolute.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
      try (OutputStream out = Files.newOutputStream(temp)) {
        offsets.store(out, "awap legacy DLQ copy checkpoint");
      }
      try {
        Files.move(
            temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to persist the DLQ copy checkpoint " + path, ex);
    }
  }

  public Map<String, Long> snapshot() {
    Map<String, Long> copy = new LinkedHashMap<>();
    offsets.stringPropertyNames().stream()
        .sorted()
        .forEach(
            key -> {
              try {
                copy.put(key, Long.parseLong(offsets.getProperty(key).trim()));
              } catch (NumberFormatException ignored) {
                // A corrupt checkpoint entry is treated as absent and recopied.
              }
            });
    return Map.copyOf(copy);
  }

  private void load() {
    if (!Files.isRegularFile(path)) {
      return;
    }
    try (InputStream in = Files.newInputStream(path)) {
      offsets.load(in);
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to read the DLQ copy checkpoint " + path, ex);
    }
  }

  private static String key(String sourceTopic, int partition) {
    return sourceTopic + "#" + partition;
  }
}
