package com.awap.engine.application;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes node-completion events from Kafka and drives the workflow engine. Consumption is
 * idempotent because {@link WorkflowEngineService#onNodeCompleted} ignores already-completed nodes.
 */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class NodeCompletedEventListener {

  private final ObjectMapper objectMapper;
  private final WorkflowEngineService workflowEngineService;
  private final KafkaTenantContext tenantContext;

  public NodeCompletedEventListener(
      ObjectMapper objectMapper,
      WorkflowEngineService workflowEngineService,
      KafkaTenantContext tenantContext) {
    this.objectMapper = objectMapper;
    this.workflowEngineService = workflowEngineService;
    this.tenantContext = tenantContext;
  }

  @KafkaListener(topics = {"workflow.task.events", "workflow.instance.events"})
  public void onMessage(String payload) {
    try {
      NodeCompletedEvent event = objectMapper.readValue(payload, NodeCompletedEvent.class);
      tenantContext.run(event.tenantId(), () -> workflowEngineService.onNodeCompleted(event));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid NodeCompletedEvent payload", ex);
    }
  }
}
