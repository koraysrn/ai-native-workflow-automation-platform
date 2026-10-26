package com.awap.engine.application;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka bridge for SUB_PROCESS completion. The in-process event already drives the parent; this
 * listener makes the signal durable across restarts and is idempotent because the parent node is
 * only completed once.
 */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class SubProcessCompletedEventListener {

  private final ObjectMapper objectMapper;
  private final WorkflowEngineService workflowEngineService;
  private final KafkaTenantContext tenantContext;

  public SubProcessCompletedEventListener(
      ObjectMapper objectMapper,
      WorkflowEngineService workflowEngineService,
      KafkaTenantContext tenantContext) {
    this.objectMapper = objectMapper;
    this.workflowEngineService = workflowEngineService;
    this.tenantContext = tenantContext;
  }

  @KafkaListener(topics = "workflow.subprocess.events")
  public void onMessage(String payload) {
    try {
      SubProcessCompletedEvent event =
          objectMapper.readValue(payload, SubProcessCompletedEvent.class);
      tenantContext.run(event.tenantId(), () -> workflowEngineService.onSubProcessCompleted(event));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid SubProcessCompletedEvent payload", ex);
    }
  }
}
