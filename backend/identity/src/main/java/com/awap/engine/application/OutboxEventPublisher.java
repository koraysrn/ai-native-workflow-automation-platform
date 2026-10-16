package com.awap.engine.application;

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
    OutboxEvent event =
        new OutboxEvent(tenantId, aggregateType, aggregateId, eventType, toJson(payload));
    repository.save(event);
    applicationEventPublisher.publishEvent(payload);
  }

  private String toJson(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize event payload", ex);
    }
  }
}
