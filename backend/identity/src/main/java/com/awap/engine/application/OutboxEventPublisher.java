package com.awap.engine.application;

import com.awap.common.correlation.CorrelationId;
import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.infrastructure.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes domain events durably to the outbox and fans them out in-process. */
@Service
public class OutboxEventPublisher {

  private final OutboxEventRepository repository;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher applicationEventPublisher;

  public OutboxEventPublisher(
      OutboxEventRepository repository,
      ObjectMapper objectMapper,
      ApplicationEventPublisher applicationEventPublisher) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.applicationEventPublisher = applicationEventPublisher;
  }

  @Transactional
  public void publish(
      UUID tenantId, String aggregateType, UUID aggregateId, String eventType, Object payload) {
    String correlationId = CorrelationId.currentOrDefault();
    OutboxEvent event =
        new OutboxEvent(
            tenantId, aggregateType, aggregateId, eventType, toJson(payload), correlationId);
    repository.save(event);
    // In-process listeners run on this thread; make sure a scheduler-originated event still carries
    // a correlation ID without leaking it into the next pooled task.
    boolean scoped = CorrelationId.current().isEmpty();
    if (scoped) {
      CorrelationId.set(correlationId);
    }
    try {
      applicationEventPublisher.publishEvent(payload);
    } finally {
      if (scoped) {
        CorrelationId.clear();
      }
    }
  }

  /**
   * Writes the event to the outbox only. Used when the caller drives the local side effect itself
   * (e.g. the webhook scheduler completes the node explicitly after a retried delivery succeeded).
   */
  @Transactional
  public void publishDurably(
      UUID tenantId, String aggregateType, UUID aggregateId, String eventType, Object payload) {
    repository.save(
        new OutboxEvent(
            tenantId,
            aggregateType,
            aggregateId,
            eventType,
            toJson(payload),
            CorrelationId.currentOrDefault()));
  }

  private String toJson(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize event payload", ex);
    }
  }
}
