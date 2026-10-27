package com.awap.engine.application;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.webhook.application.WebhookAttemptResult;
import com.awap.webhook.infrastructure.WebhookClient;
import com.awap.workflow.domain.NodeDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Performs the synchronous (first) attempt of a WEBHOOK node. Only one HTTP request is issued here:
 * retryable failures are handed over to the durable {@code webhook_deliveries} queue instead of
 * being retried in-thread. Validation failures are reported as permanent (400/413) so the caller
 * can persist an operator-visible DEAD row.
 */
@Component
public class WebhookInvoker {

  static final int MAX_PAYLOAD_BYTES = 1_048_576;

  private final WebhookClient webhookClient;
  private final NodeConfigReader configReader;
  private final ObjectMapper objectMapper;

  @Autowired
  public WebhookInvoker(WebhookClient webhookClient, ObjectMapper objectMapper) {
    this(webhookClient, new NodeConfigReader(objectMapper), objectMapper);
  }

  WebhookInvoker(WebhookClient webhookClient, NodeConfigReader configReader, ObjectMapper mapper) {
    this.webhookClient = webhookClient;
    this.configReader = configReader;
    this.objectMapper = mapper;
  }

  /** Runs the synchronous first attempt and returns its classified outcome. */
  public WebhookAttemptResult invoke(
      WorkflowInstance instance, NodeDefinition node, NodeExecution execution) {
    String url = configReader.readWebhookUrl(node);
    if (url == null) {
      return WebhookAttemptResult.rejected(
          400, "WEBHOOK node '" + node.getNodeId() + "' requires a url in its config.");
    }
    String payload = configReader.readWebhookPayload(node);
    if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
      return WebhookAttemptResult.rejected(413, "Webhook payload exceeds the 1 MB limit.");
    }
    if (!isJson(payload)) {
      return WebhookAttemptResult.rejected(400, "Webhook payload is not valid JSON.");
    }
    Duration timeout = Duration.ofSeconds(Math.max(1, configReader.readTimeoutSeconds(node)));
    return webhookClient.attempt(url, payload, idempotencyKey(instance, node, execution), timeout);
  }

  /** Configured target URL of the node, or {@code null} when the node is misconfigured. */
  public String targetUrl(NodeDefinition node) {
    return configReader.readWebhookUrl(node);
  }

  /** Immutable payload of the node; unchanged for every retry attempt. */
  public String payload(NodeDefinition node) {
    return configReader.readWebhookPayload(node);
  }

  /** Stable across every attempt and replica: {@code instanceId:nodeId:executionId}. */
  String idempotencyKey(WorkflowInstance instance, NodeDefinition node, NodeExecution execution) {
    return instance.getId() + ":" + node.getNodeId() + ":" + execution.getId();
  }

  private boolean isJson(String payload) {
    try {
      objectMapper.readTree(payload);
      return true;
    } catch (JsonProcessingException ex) {
      return false;
    }
  }
}
