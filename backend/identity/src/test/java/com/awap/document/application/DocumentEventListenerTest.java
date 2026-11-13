package com.awap.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parsing, tenant context and DLQ-facing failure behaviour of the document consumer (issue #208).
 */
class DocumentEventListenerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DocumentService service = mock(DocumentService.class);
  private final KafkaTenantContext tenantContext = mock(KafkaTenantContext.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final UUID tenantId = UUID.randomUUID();
  private final UUID fileId = UUID.randomUUID();

  private DocumentEventListener listener;

  @BeforeEach
  void setUp() {
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(1)).run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());
    listener = new DocumentEventListener(objectMapper, service, tenantContext, registry);
    when(service.applyEvent(any())).thenReturn(Optional.empty());
  }

  private double failures() {
    var counter = registry.find(DocumentEventListener.FAILURES_METRIC).counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void parsesTheLifecyclePayloadAndAppliesItInsideTheTenantContext() {
    String payload =
        "{\"tenantId\":\""
            + tenantId
            + "\",\"fileId\":\""
            + fileId
            + "\",\"eventType\":\"DOCUMENT_VERSION_CREATED\",\"version\":2,"
            + "\"size\":42,\"format\":\"pdf\",\"objectKey\":\""
            + fileId
            + "/v2\"}";

    listener.onMessage(payload);

    verify(service)
        .applyEvent(
            new DocumentLifecycleEvent(
                tenantId, fileId, "DOCUMENT_VERSION_CREATED", 2, 42L, "pdf", fileId + "/v2"));
    assertThat(failures()).isZero();
  }

  @Test
  void aMalformedPayloadIsRejectedForTheDlqPath() {
    assertThatThrownBy(() -> listener.onMessage("{\"tenantId\":\"not-a-uuid\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid DocumentEvent payload");
    assertThat(failures()).isEqualTo(1.0);
  }

  @Test
  void aPayloadWithoutTenantOrFileIsRejected() {
    assertThatThrownBy(() -> listener.onMessage("{\"eventType\":\"DOCUMENT_REGISTERED\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId and fileId");
    assertThat(failures()).isEqualTo(1.0);
  }

  @Test
  void aPayloadWithoutEventTypeIsRejected() {
    String payload =
        "{\"tenantId\":\"" + tenantId + "\",\"fileId\":\"" + fileId + "\",\"eventType\":\" \"}";

    assertThatThrownBy(() -> listener.onMessage(payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventType");
    assertThat(failures()).isEqualTo(1.0);
    verify(service, never()).applyEvent(any());
  }

  @Test
  void aServiceFailureIsCountedAndRethrownForRetryThenDlq() {
    doThrow(new IllegalStateException("database down")).when(service).applyEvent(any());
    String payload =
        "{\"tenantId\":\""
            + tenantId
            + "\",\"fileId\":\""
            + fileId
            + "\",\"eventType\":\"DOCUMENT_REGISTERED\",\"version\":1}";

    assertThatThrownBy(() -> listener.onMessage(payload))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database down");
    assertThat(failures()).isEqualTo(1.0);
  }
}
