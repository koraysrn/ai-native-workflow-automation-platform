package com.awap.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dedicated escalation consumer parses, guards and dead-letters its own payloads (issue #207).
 */
class TaskEscalationEventListenerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TaskEscalationService service = mock(TaskEscalationService.class);
  private final KafkaTenantContext tenantContext = mock(KafkaTenantContext.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private TaskEscalationEventListener listener;

  @BeforeEach
  void setUp() {
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(1)).run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());
    listener = new TaskEscalationEventListener(objectMapper, service, tenantContext, registry);
  }

  private double failures() {
    var counter = registry.find(TaskEscalationEventListener.FAILURES_METRIC).counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void parsesThePayloadAndRunsInsideTheTenantContext() {
    UUID tenantId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID backupId = UUID.randomUUID();
    String payload =
        "{\"tenantId\":\""
            + tenantId
            + "\",\"taskId\":\""
            + taskId
            + "\",\"assigneeId\":\""
            + backupId
            + "\"}";

    listener.onMessage(payload);

    verify(service).escalate(new TaskEscalationDue(tenantId, taskId, backupId));
    assertThat(failures()).isZero();
  }

  @Test
  void aMalformedPayloadIsRejectedSoItCanBeDeadLettered() {
    assertThatThrownBy(() -> listener.onMessage("{\"tenantId\":\"not-a-uuid\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid TaskEscalationDue payload");
    assertThat(failures()).isEqualTo(1.0);
  }

  @Test
  void aServiceFailureIsCountedAndRethrownForRetryThenDlq() {
    UUID tenantId = UUID.randomUUID();
    doThrow(new IllegalStateException("database down"))
        .when(service)
        .escalate(any(TaskEscalationDue.class));
    String payload =
        "{\"tenantId\":\""
            + tenantId
            + "\",\"taskId\":\""
            + UUID.randomUUID()
            + "\",\"assigneeId\":null}";

    assertThatThrownBy(() -> listener.onMessage(payload))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database down");
    assertThat(failures()).isEqualTo(1.0);
  }
}
