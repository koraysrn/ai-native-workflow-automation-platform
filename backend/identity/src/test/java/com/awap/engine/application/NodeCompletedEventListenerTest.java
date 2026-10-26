package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeCompletedEventListenerTest {

  @Mock private WorkflowEngineService workflowEngineService;
  @Mock private KafkaTenantContext tenantContext;

  private NodeCompletedEventListener listener;

  @BeforeEach
  void setUp() {
    lenient()
        .doAnswer(
            invocation -> {
              Runnable action = invocation.getArgument(1);
              action.run();
              return null;
            })
        .when(tenantContext)
        .run(any(), any());
    listener =
        new NodeCompletedEventListener(
            new ObjectMapper().findAndRegisterModules(), workflowEngineService, tenantContext);
  }

  @Test
  void onMessageDeserializesAndDelegates() {
    String payload =
        "{\"tenantId\":\""
            + UUID.randomUUID()
            + "\",\"workflowInstanceId\":\""
            + UUID.randomUUID()
            + "\",\"nodeExecutionId\":\""
            + UUID.randomUUID()
            + "\",\"outputJson\":\"{}\"}";

    listener.onMessage(payload);

    verify(workflowEngineService).onNodeCompleted(any(NodeCompletedEvent.class));
  }

  @Test
  void onMessageInvalidJsonThrows() {
    assertThatThrownBy(() -> listener.onMessage("not-json"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
