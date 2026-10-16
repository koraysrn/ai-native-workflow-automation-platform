package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

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

  private NodeCompletedEventListener listener;

  @BeforeEach
  void setUp() {
    listener =
        new NodeCompletedEventListener(
            new ObjectMapper().findAndRegisterModules(), workflowEngineService);
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
