package com.awap.usage.interfaces.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.awap.identity.infrastructure.security.KafkaTenantContext;
import com.awap.identity.infrastructure.security.TenantTransactionRunner;
import com.awap.usage.application.InMemoryUsageCounterStore;
import com.awap.usage.application.UsageMeteringService;
import com.awap.usage.domain.UsageMetric;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UsageEventListenerTest {

  private final UUID tenantId = UUID.randomUUID();
  private final UUID instanceId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private final InMemoryUsageCounterStore store = new InMemoryUsageCounterStore();
  private final UsageMeteringService metering =
      new UsageMeteringService(
          store, new SimpleMeterRegistry(), false, java.time.Clock.systemUTC());

  private UsageEventListener listener;

  @BeforeEach
  void setUp() {
    TenantTransactionRunner runner = mock(TenantTransactionRunner.class);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(runner)
        .run(any());
    listener = new UsageEventListener(new ObjectMapper(), metering, new KafkaTenantContext(runner));
  }

  @Test
  void aRetriedKafkaEventIncrementsTheCounterOnlyOnce() {
    String payload =
        """
        {"tenantId":"%s","eventType":"WORKFLOW_STARTED","workflowInstanceId":"%s","timestamp":1}
        """
            .formatted(tenantId, instanceId);

    listener.onMessage(payload);
    listener.onMessage(payload);

    assertThat(
            store.value(
                tenantId, LocalDate.now(java.time.ZoneOffset.UTC), UsageMetric.WORKFLOW_EXECUTION))
        .isEqualTo(1L);
  }

  @Test
  void taskDecisionEventIncrementsTaskDecisionMetric() {
    String payload =
        """
        {"tenantId":"%s","eventType":"APPROVAL_COMPLETED","taskId":"%s","timestamp":1}
        """
            .formatted(tenantId, taskId);

    listener.onMessage(payload);

    assertThat(
            store.value(
                tenantId, LocalDate.now(java.time.ZoneOffset.UTC), UsageMetric.TASK_DECISION))
        .isEqualTo(1L);
  }

  @Test
  void workflowStartedAndTaskDecisionDedupeWithTheSynchronousHookKeys() {
    listener.onMessage(
        """
        {"tenantId":"%s","eventType":"WORKFLOW_STARTED","workflowInstanceId":"%s","timestamp":1}
        """
            .formatted(tenantId, instanceId));

    metering.record(
        tenantId,
        UsageMetric.WORKFLOW_EXECUTION,
        1L,
        com.awap.usage.domain.UsageEventKeys.workflowStarted(instanceId));

    assertThat(
            store.value(
                tenantId, LocalDate.now(java.time.ZoneOffset.UTC), UsageMetric.WORKFLOW_EXECUTION))
        .isEqualTo(1L);
  }

  @Test
  void unmappedEventTypeIsIgnored() {
    listener.onMessage(
        """
        {"tenantId":"%s","eventType":"WORKFLOW_COMPLETED","workflowInstanceId":"%s","timestamp":1}
        """
            .formatted(tenantId, instanceId));

    assertThat(
            store.value(
                tenantId, LocalDate.now(java.time.ZoneOffset.UTC), UsageMetric.WORKFLOW_EXECUTION))
        .isZero();
  }

  @Test
  void missingAggregateIdIsIgnored() {
    listener.onMessage(
        """
        {"tenantId":"%s","eventType":"WORKFLOW_STARTED","timestamp":1}
        """
            .formatted(tenantId));

    assertThat(
            store.value(
                tenantId, LocalDate.now(java.time.ZoneOffset.UTC), UsageMetric.WORKFLOW_EXECUTION))
        .isZero();
  }

  @Test
  void malformedPayloadIsRejectedForTheDlq() {
    assertThatThrownBy(() -> listener.onMessage("{not-json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid AnalyticsEvent payload");
  }
}
