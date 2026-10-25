package com.awap.engine.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the producer Avro schemas (issue #97). Asserts every field's presence and type
 * so a rename or type change in the producer schema breaks the build before the Schema Registry
 * compatibility gate runs.
 */
class AvroEventContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void notificationRequestedEventContract() throws Exception {
    Map<String, String> fields = fields("NotificationRequestedEvent.avsc");

    assertThat(fields)
        .containsOnly(
            Map.entry("tenantId", "string"),
            Map.entry("recipient", "string"),
            Map.entry("channel", "string"),
            Map.entry("templateKey", "string"),
            Map.entry("variablesJson", "null|string"));
  }

  @Test
  void analyticsEventContract() throws Exception {
    Map<String, String> fields = fields("AnalyticsEvent.avsc");

    assertThat(fields)
        .containsOnly(
            Map.entry("tenantId", "string"),
            Map.entry("eventType", "string"),
            Map.entry("workflowInstanceId", "null|string"),
            Map.entry("taskId", "null|string"),
            Map.entry("durationMs", "null|long"),
            Map.entry("timestamp", "long"));
  }

  @Test
  void documentEventContract() throws Exception {
    Map<String, String> fields = fields("DocumentEvent.avsc");

    assertThat(fields)
        .containsOnly(
            Map.entry("tenantId", "string"),
            Map.entry("fileId", "string"),
            Map.entry("eventType", "string"),
            Map.entry("size", "long"),
            Map.entry("format", "null|string"));
  }

  private Map<String, String> fields(String fileName) throws Exception {
    Path schema = Path.of("src/main/avro", fileName);
    JsonNode root = objectMapper.readTree(Files.readString(schema));
    Map<String, String> fields = new HashMap<>();
    for (JsonNode field : root.path("fields")) {
      String type =
          field.path("type").isArray()
              ? field.path("type").path(0).asText() + "|" + field.path("type").path(1).asText()
              : field.path("type").asText();
      fields.put(field.path("name").asText(), type);
    }
    return fields;
  }
}
