package com.awap.notification.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.notification.domain.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRequestedEventListenerTest {

  private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final NotificationService notificationService = mock(NotificationService.class);
  private final KafkaTenantContext tenantContext = mock(KafkaTenantContext.class);
  private final NotificationRequestedEventListener listener =
      new NotificationRequestedEventListener(objectMapper, notificationService, tenantContext);

  @Test
  void forwardsExplicitDedupeKeyBusinessKeyAndEventType() {
    runInContext();
    listener.onMessage(
        """
        {"tenantId":"%s","recipient":"a@example.com","channel":"EMAIL","templateKey":"welcome",
         "locale":"tr","variablesJson":null,"dedupeKey":"explicit-1",
         "businessKey":"approval-42","eventType":"APPROVAL_REQUESTED"}
        """
            .formatted(TENANT));

    verify(notificationService)
        .send(
            eq(TENANT),
            eq("a@example.com"),
            eq(NotificationChannel.EMAIL),
            eq("welcome"),
            eq("tr"),
            eq(null),
            eq("explicit-1"),
            eq("approval-42"),
            eq("APPROVAL_REQUESTED"));
  }

  @Test
  void derivesTheBusinessKeyFromAWorkflowInstanceId() {
    runInContext();
    listener.onMessage(
        """
        {"tenantId":"%s","recipient":"a@example.com","channel":"EMAIL","templateKey":"welcome",
         "workflowInstanceId":"11111111-1111-1111-1111-111111111111","eventType":"WORKFLOW_COMPLETED"}
        """
            .formatted(TENANT));

    verify(notificationService)
        .send(
            eq(TENANT),
            eq("a@example.com"),
            eq(NotificationChannel.EMAIL),
            eq("welcome"),
            eq("en"),
            any(),
            eq(null),
            eq("11111111-1111-1111-1111-111111111111"),
            eq("WORKFLOW_COMPLETED"));
  }

  @Test
  void prefersTheExplicitBusinessKeyOverTheAggregateIds() {
    runInContext();
    listener.onMessage(
        """
        {"tenantId":"%s","recipient":"a@example.com","channel":"EMAIL","templateKey":"welcome",
         "taskId":"task-1","businessKey":"composite-key","eventType":"TASK_ASSIGNED"}
        """
            .formatted(TENANT));

    verify(notificationService)
        .send(
            eq(TENANT),
            eq("a@example.com"),
            eq(NotificationChannel.EMAIL),
            eq("welcome"),
            eq("en"),
            any(),
            eq(null),
            eq("composite-key"),
            eq("TASK_ASSIGNED"));
  }

  private void runInContext() {
    doAnswer(
            invocation -> {
              Runnable action = invocation.getArgument(1);
              action.run();
              return null;
            })
        .when(tenantContext)
        .run(eq(TENANT), any());
  }
}
