package com.awap.webhook.application;

import java.util.UUID;

/**
 * Engine-side effects the delivery queue triggers. Implemented by the engine module so the webhook
 * module never depends back on engine internals (hexagonal/port-adapter boundary).
 */
public interface WebhookDeliveryNodePort {

  /** A retried delivery succeeded: write {@code NodeCompletedEvent} to the outbox and complete. */
  void nodeCompleted(
      UUID tenantId,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      UUID deliveryId,
      String outputJson,
      int statusCode);

  /** A delivery is DEAD: publish the DLQ event/metric and fail the node and its instance. */
  void deliveryDead(
      UUID tenantId,
      UUID workflowInstanceId,
      UUID nodeExecutionId,
      UUID deliveryId,
      int attempts,
      String error);
}
