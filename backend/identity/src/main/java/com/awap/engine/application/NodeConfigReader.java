package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.workflow.domain.NodeDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;

/**
 * Pure parser for a workflow node's {@code config_json}. Malformed or missing configuration falls
 * back to safe defaults instead of failing the workflow.
 */
final class NodeConfigReader {

  private final ObjectMapper objectMapper;

  NodeConfigReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Returns the {@code delaySeconds} value, or 0 when absent or malformed. */
  long readDelaySeconds(NodeDefinition node) {
    String configJson = node.getConfigJson();
    if (configJson == null || configJson.isBlank()) {
      return 0;
    }
    try {
      JsonNode config = objectMapper.readTree(configJson);
      return config.path("delaySeconds").asLong(0);
    } catch (JsonProcessingException ex) {
      return 0;
    }
  }

  /** Returns the {@code joinNodeId} value, or {@code null} when absent or malformed. */
  String readJoinNodeId(NodeDefinition node) {
    String configJson = node.getConfigJson();
    if (configJson == null || configJson.isBlank()) {
      return null;
    }
    try {
      JsonNode config = objectMapper.readTree(configJson);
      return config.path("joinNodeId").asText(null);
    } catch (JsonProcessingException ex) {
      return null;
    }
  }

  /** Returns the optional guard expression configured directly on the node. */
  Optional<String> readExpression(NodeDefinition node) {
    return readText(node, "expression");
  }

  String readWebhookUrl(NodeDefinition node) {
    return readText(node, "url").orElse(null);
  }

  String readWebhookPayload(NodeDefinition node) {
    return readText(node, "payload").orElse("{}");
  }

  long readTimeoutSeconds(NodeDefinition node) {
    return readLong(node, "timeoutSeconds", 10);
  }

  /**
   * Raw {@code timeoutSeconds} value exactly as configured, without clamping.
   *
   * <p>Used by the timeout resolver, which must reject zero, negative and absurd values instead of
   * silently correcting them.
   */
  OptionalLong readConfiguredTimeoutSeconds(NodeDefinition node) {
    JsonNode config = readConfig(node);
    JsonNode value = config.get("timeoutSeconds");
    if (value == null || !value.isNumber()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(value.asLong());
  }

  int readMaxAttempts(NodeDefinition node) {
    long configured = readLong(node, "maxAttempts", 3);
    return (int) Math.max(1, Math.min(configured, 10));
  }

  long readRetryBackoffMs(NodeDefinition node) {
    return readLong(node, "retryBackoffMs", 1000);
  }

  String readSubProcessDefinitionId(NodeDefinition node) {
    return readText(node, "workflowDefinitionId").orElse(null);
  }

  /**
   * Returns the configured SUB_PROCESS version, or empty when absent. A configured version must be
   * a positive integer; malformed values fail the node instead of silently falling back to the
   * current published version.
   */
  Optional<Integer> readSubProcessVersion(NodeDefinition node) {
    JsonNode value = readConfig(node).get("version");
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (value.isIntegralNumber()) {
      int version = value.intValue();
      if (version > 0) {
        return Optional.of(version);
      }
      throw invalidSubProcessVersion(value.asText());
    }
    if (value.isTextual()) {
      String raw = value.asText().trim();
      try {
        int version = Integer.parseInt(raw);
        if (version > 0) {
          return Optional.of(version);
        }
      } catch (NumberFormatException ex) {
        // fall through to the explicit validation error below
      }
      throw invalidSubProcessVersion(raw);
    }
    throw invalidSubProcessVersion(value.toString());
  }

  private AwapException invalidSubProcessVersion(String raw) {
    return new AwapException(
        ErrorCode.WORKFLOW_VERSION_INVALID,
        HttpStatus.BAD_REQUEST,
        "SUB_PROCESS version must be a positive integer, but was '" + raw + "'.");
  }

  /** Returns the {@code approvers} string array, or an empty list when absent or malformed. */
  List<String> readApprovers(NodeDefinition node) {
    String configJson = node.getConfigJson();
    if (configJson == null || configJson.isBlank()) {
      return List.of();
    }
    try {
      JsonNode config = objectMapper.readTree(configJson);
      JsonNode approvers = config.get("approvers");
      List<String> result = new ArrayList<>();
      if (approvers != null && approvers.isArray()) {
        for (JsonNode approver : approvers) {
          String value = approver.asText(null);
          if (value != null) {
            result.add(value);
          }
        }
      }
      return result;
    } catch (JsonProcessingException ex) {
      return List.of();
    }
  }

  Optional<String> readScript(NodeDefinition node) {
    return readText(node, "script");
  }

  Optional<String> readAiPrompt(NodeDefinition node) {
    return readText(node, "prompt");
  }

  String readAiAction(NodeDefinition node) {
    return readText(node, "action")
        .map(value -> value.toUpperCase(java.util.Locale.ROOT))
        .orElse("SUMMARIZE");
  }

  String readContextJson(NodeDefinition node) {
    JsonNode context = readConfig(node).get("context");
    return context == null || context.isNull() ? "{}" : context.toString();
  }

  /**
   * Reads the optional {@code compensation} block. Missing or malformed configuration yields an
   * empty result, so a node without a declared compensating action is simply not part of a saga.
   * Only {@code WEBHOOK} compensations are executable; future integration adapters can add types
   * without changing the persistence model.
   */
  Optional<SagaCompensationConfig> readSagaCompensation(NodeDefinition node) {
    JsonNode compensation = readConfig(node).get("compensation");
    if (compensation == null || !compensation.isObject()) {
      return Optional.empty();
    }
    String type =
        compensation.path("type").asText("WEBHOOK").trim().toUpperCase(java.util.Locale.ROOT);
    if (!"WEBHOOK".equals(type)) {
      return Optional.empty();
    }
    String url = compensation.path("url").asText(null);
    if (url == null || url.isBlank()) {
      return Optional.empty();
    }
    String payload = compensation.path("payload").asText("{}");
    int timeoutSeconds = clamp(compensation.path("timeoutSeconds").asInt(10), 1, 300);
    int maxAttempts = clamp(compensation.path("maxAttempts").asInt(3), 1, 10);
    long retryBackoffMs = clamp(compensation.path("retryBackoffMs").asLong(1000L), 1L, 600_000L);
    return Optional.of(
        new SagaCompensationConfig(
            type, url, payload, timeoutSeconds, maxAttempts, retryBackoffMs));
  }

  private int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }

  private long clamp(long value, long minimum, long maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }

  private Optional<String> readText(NodeDefinition node, String field) {
    JsonNode config = readConfig(node);
    JsonNode value = config.get(field);
    return value == null || value.isNull() || value.asText().isBlank()
        ? Optional.empty()
        : Optional.of(value.asText());
  }

  private long readLong(NodeDefinition node, String field, long defaultValue) {
    JsonNode config = readConfig(node);
    JsonNode value = config.get(field);
    return value == null || !value.isNumber() ? defaultValue : value.asLong(defaultValue);
  }

  private JsonNode readConfig(NodeDefinition node) {
    String configJson = node.getConfigJson();
    if (configJson == null || configJson.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(configJson);
    } catch (JsonProcessingException ex) {
      return objectMapper.createObjectNode();
    }
  }
}
