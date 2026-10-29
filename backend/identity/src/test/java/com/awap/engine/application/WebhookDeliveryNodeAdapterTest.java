package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Engine adapter: retried success writes NodeCompletedEvent; exhausted delivery emits DLQ event.
 */
class WebhookDeliveryNodeAdapterTest {

  private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
  private final WorkflowEngineService workflowEngineService = mock(WorkflowEngineService.class);
  private final WebhookDeliveryNodeAdapter adapter =
      new WebhookDeliveryNodeAdapter(outboxEventPublisher, workflowEngineService);

  private final UUID tenantId = UUID.randomUUID();
  private final UUID instanceId = UUID.randomUUID();
  private final UUID executionId = UUID.randomUUID();
  private final UUID deliveryId = UUID.randomUUID();

  @Test
  void successfulRetryWritesTheOutboxEventAndCompletesTheNode() {
    adapter.nodeCompleted(tenantId, instanceId, executionId, deliveryId, "{\"ok\":true}", 200);

    NodeCompletedEvent expected =
        new NodeCompletedEvent(tenantId, instanceId, executionId, "{\"ok\":true}");
    verify(outboxEventPublisher)
        .publishDurably(tenantId, "WEBHOOK_DELIVERY", deliveryId, "NODE_COMPLETED", expected);
    verify(workflowEngineService).onNodeCompleted(expected);
  }

  @Test
  void deadDeliveryEmitsTheDlqEventAndFailsTheNode() {
    adapter.deliveryDead(tenantId, instanceId, executionId, deliveryId, 3, "status 500");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(outboxEventPublisher)
        .publishDurably(
            eq(tenantId),
            eq("WEBHOOK_DELIVERY"),
            eq(deliveryId),
            eq("WEBHOOK_DELIVERY_DEAD"),
            payload.capture());

    assertThat(payload.getValue())
        .containsEntry("tenantId", tenantId.toString())
        .containsEntry("deliveryId", deliveryId.toString())
        .containsEntry("workflowInstanceId", instanceId.toString())
        .containsEntry("nodeExecutionId", executionId.toString())
        .containsEntry("attempts", 3)
        .containsEntry("error", "status 500");
    verify(workflowEngineService)
        .failNodeFromWebhookDelivery(eq(tenantId), eq(instanceId), eq(executionId), anyString());
  }
}
