package com.awap.engine.application;

import com.awap.workflow.domain.NodeDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

  Optional<Integer> readSubProcessVersion(NodeDefinition node) {
    return readText(node, "version").map(Integer::parseInt);
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
