package com.awap.engine.application;

import com.awap.webhook.application.WebhookDeliveryNodePort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Engine side of the webhook delivery queue: turns a successful retried delivery into a durable
 * {@code NodeCompletedEvent} plus node completion, and a DEAD delivery into an outbox DLQ event
 * plus a failed node/instance.
 */
@Component
public class WebhookDeliveryNodeAdapter implements WebhookDeliveryNodePort {

  private static final String WEBHOOK_AGGREGATE = "WEBHOOK_DELIVERY";

  private final OutboxEventPublisher outboxEventPublisher;
  private final WorkflowEngineService workflowEngineService;

  public WebhookDeliveryNodeAdapter(
      OutboxEventPublisher outboxEventPublisher, WorkflowEngineService workflowEngineService) {
    this.outboxEventPublisher = outboxEventPublisher;
    this.workflowEngineService = workflowEngineService;
  }

  @Override
  public void nodeCompleted(
      UUID tenantId,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      UUID deliveryId,
      String outputJson,
      int statusCode) {
    NodeCompletedEvent event =
        new NodeCompletedEvent(tenantId, workflowInstanceId, nodeExecutionId, outputJson);
    outboxEventPublisher.publishDurably(
        tenantId, WEBHOOK_AGGREGATE, deliveryId, "NODE_COMPLETED", event);
    workflowEngineService.onNodeCompleted(event);
  }

  @Override
  public void deliveryDead(
      UUID tenantId,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      UUID deliveryId,
      int attempts,
      String error) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", tenantId.toString());
    payload.put("deliveryId", deliveryId.toString());
    payload.put("workflowInstanceId", workflowInstanceId.toString());
    payload.put("nodeExecutionId", nodeExecutionId.toString());
    payload.put("attempts", attempts);
    payload.put("error", error);
    outboxEventPublisher.publishDurably(
        tenantId, WEBHOOK_AGGREGATE, deliveryId, "WEBHOOK_DELIVERY_DEAD", payload);
    workflowEngineService.failNodeFromWebhookDelivery(
        tenantId,
        workflowInstanceId,
        nodeExecutionId,
        "Webhook delivery exhausted after " + attempts + " attempt(s): " + error);
  }
}
