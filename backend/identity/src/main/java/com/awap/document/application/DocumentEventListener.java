package com.awap.document.application;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the {@code document.events} pipeline and re-applies each lifecycle fact idempotently.
 *
 * <p>Every record carries its tenant id, file id, version and object key, so the consumer never
 * invents a new version: a duplicate delivery finds the {@code (file_id, version)} row and is
 * counted as a duplicate, which breaks the old self-triggered version loop. A payload that cannot
 * be parsed (or has no tenant/file/type) is rejected and routed to {@code document.events.dlq} by
 * the shared Kafka error handler, and the failure counter makes it alertable.
 */
@Component
@ConditionalOnProperty(name = "awap.kafka.enabled", havingValue = "true")
public class DocumentEventListener {

  public static final String TOPIC = "document.events";
  public static final String FAILURES_METRIC = "awap.document.event.failures";

  private final ObjectMapper objectMapper;
  private final DocumentService documentService;
  private final KafkaTenantContext tenantContext;
  private final MeterRegistry meterRegistry;

  public DocumentEventListener(
      ObjectMapper objectMapper,
      DocumentService documentService,
      KafkaTenantContext tenantContext,
      MeterRegistry meterRegistry) {
    this.objectMapper = objectMapper;
    this.documentService = documentService;
    this.tenantContext = tenantContext;
    this.meterRegistry = meterRegistry;
  }

  @KafkaListener(topics = TOPIC)
  public void onMessage(String payload) {
    try {
      DocumentLifecycleEvent event = objectMapper.readValue(payload, DocumentLifecycleEvent.class);
      if (event.tenantId() == null || event.fileId() == null) {
        throw new IllegalArgumentException(
            "Invalid DocumentEvent payload: tenantId and fileId are required.");
      }
      requireEventType(event.eventType());
      tenantContext.run(event.tenantId(), () -> documentService.applyEvent(event));
    } catch (JsonProcessingException ex) {
      counter().increment();
      throw new IllegalArgumentException("Invalid DocumentEvent payload", ex);
    } catch (RuntimeException ex) {
      counter().increment();
      throw ex;
    }
  }

  private static void requireEventType(String eventType) {
    if (eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("Invalid DocumentEvent payload: eventType is required.");
    }
  }

  private Counter counter() {
    return Counter.builder(FAILURES_METRIC).register(meterRegistry);
  }
}
