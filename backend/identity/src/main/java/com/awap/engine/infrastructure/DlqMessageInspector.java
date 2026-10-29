package com.awap.engine.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Detects payloads that cannot be handed to the DLQ workflow safely.
 *
 * <p>A dead-letter record is not reprocessed: it is logged and counted. A payload that is blank or
 * that claims to be JSON but cannot be parsed is quarantined to a local file so it cannot poison
 * the consumer loop and cannot be lost either.
 */
@Component
public class DlqMessageInspector {

  private final ObjectMapper objectMapper;

  public DlqMessageInspector(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Optional<String> malformedReason(String payload) {
    if (payload == null) {
      return Optional.of("null-payload");
    }
    if (payload.isBlank()) {
      return Optional.of("blank-payload");
    }
    String trimmed = payload.strip();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        objectMapper.readTree(trimmed);
      } catch (JsonProcessingException ex) {
        return Optional.of("invalid-json");
      }
    }
    return Optional.empty();
  }
}
