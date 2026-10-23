package com.awap.engine.application;

import com.awap.engine.domain.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Appends purged outbox events to a JSON Lines file, preserving them before deletion. */
@Component
public class JsonlOutboxEventArchiver implements OutboxEventArchiver {

  private final Path archiveFile;
  private final ObjectMapper objectMapper;

  public JsonlOutboxEventArchiver(
      ObjectMapper objectMapper,
      @Value("${awap.outbox.archive-path:target/outbox-archive/outbox.jsonl}") String archivePath) {
    this.objectMapper = objectMapper;
    this.archiveFile = Path.of(archivePath);
  }

  @Override
  public synchronized void archive(OutboxEvent event) {
    try {
      Files.createDirectories(archiveFile.getParent());
      String line = objectMapper.writeValueAsString(toMap(event)) + System.lineSeparator();
      Files.writeString(
          archiveFile,
          line,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to archive outbox event " + event.getId(), ex);
    }
  }

  private Map<String, Object> toMap(OutboxEvent event) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("id", event.getId().toString());
    record.put("tenantId", event.getTenantId().toString());
    record.put("aggregateType", event.getAggregateType());
    record.put("aggregateId", event.getAggregateId().toString());
    record.put("eventType", event.getEventType());
    record.put("payloadJson", event.getPayloadJson());
    record.put("createdAt", event.getCreatedAt().toString());
    record.put("published", event.isPublished());
    return record;
  }
}
