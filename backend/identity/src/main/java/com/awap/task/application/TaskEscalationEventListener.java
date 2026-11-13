package com.awap.task.application;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dedicated consumer for {@code task.escalation.events} (issue #207).
 *
 * <p>It deliberately does not share a topic with {@code workflow.task.events}: the node-completion
 * listener parses every record there as a {@code NodeCompletedEvent}, which turned escalations into
 * poison records and DLQ noise. A malformed escalation payload is still rejected and therefore
 * dead-lettered to {@code task.escalation.events.dlq} by the shared Kafka error handler.
 */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class TaskEscalationEventListener {

  public static final String TOPIC = "task.escalation.events";
  public static final String FAILURES_METRIC = "awap.task.escalation.failures";

  private final ObjectMapper objectMapper;
  private final TaskEscalationService escalationService;
  private final KafkaTenantContext tenantContext;
  private final MeterRegistry meterRegistry;

  public TaskEscalationEventListener(
      ObjectMapper objectMapper,
      TaskEscalationService escalationService,
      KafkaTenantContext tenantContext,
      MeterRegistry meterRegistry) {
    this.objectMapper = objectMapper;
    this.escalationService = escalationService;
    this.tenantContext = tenantContext;
    this.meterRegistry = meterRegistry;
  }

  @KafkaListener(topics = TOPIC)
  public void onMessage(String payload) {
    try {
      TaskEscalationDue event = objectMapper.readValue(payload, TaskEscalationDue.class);
      tenantContext.run(event.tenantId(), () -> escalationService.escalate(event));
    } catch (JsonProcessingException ex) {
      counter().increment();
      throw new IllegalArgumentException("Invalid TaskEscalationDue payload", ex);
    } catch (RuntimeException ex) {
      // The shared error handler retries and then publishes to task.escalation.events.dlq; the
      // counter makes repeated escalation failures alertable instead of log-only.
      counter().increment();
      throw ex;
    }
  }

  private Counter counter() {
    return Counter.builder(FAILURES_METRIC).register(meterRegistry);
  }
}
