package com.awap.engine.application;

import com.awap.workflow.domain.NodeDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

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
}
