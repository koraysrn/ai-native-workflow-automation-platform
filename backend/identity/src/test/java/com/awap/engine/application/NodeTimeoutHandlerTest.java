package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeTimeoutHandlerTest {

  @Mock NodeExecutionRepository nodeExecutionRepository;
  @Mock WorkflowEnginePort port;

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final NodeTimeoutMetrics metrics = new NodeTimeoutMetrics(registry);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final UUID tenantId = UUID.randomUUID();

  private NodeTimeoutHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NodeTimeoutHandler(
            nodeExecutionRepository,
            new NodeConfigReader(objectMapper),
            port,
            metrics,
            objectMapper);
  }

  private WorkflowInstance instance() {
    return new WorkflowInstance(tenantId, UUID.randomUUID(), 1, WorkflowState.RUNNING, null, "{}");
  }

  private NodeDefinition node(NodeType type, String configJson) {
    return new NodeDefinition(tenantId, UUID.randomUUID(), "hook", type, "Hook", configJson, 0);
  }

  private NodeExecution execution(WorkflowInstance instance, int attempts) {
    NodeExecution execution = new NodeExecution(tenantId, instance.getId(), "hook", "WEBHOOK");
    for (int i = 0; i < attempts; i++) {
      execution.start();
    }
    return execution;
  }

  @Test
  void retryableTimeoutSchedulesExactlyOneRetryAndIsIdempotent() {
    WorkflowInstance instance = instance();
    NodeExecution execution = execution(instance, 1);
    NodeDefinition node = node(NodeType.WEBHOOK, "{\"maxAttempts\":3,\"retryBackoffMs\":500}");
    NodeTimeoutResolution resolution =
        NodeTimeoutResolution.supported(NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1));

    handler.handle(
        instance,
        node,
        execution,
        resolution,
        Duration.ofMillis(1200),
        "Node 'hook' exceeded its 1000ms timeout after 1200ms.",
        false);

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    assertThat(execution.getResumeAt()).isNotNull();
    assertThat(instance.getState()).isEqualTo(WorkflowState.RUNNING);
    verify(nodeExecutionRepository, times(1)).save(execution);
    verify(port, never()).transition(any(), any(), any());
    assertThat(registry.find(NodeTimeoutMetrics.TIMEOUT_FAILURES).counter()).isNull();

    Instant scheduledResume = execution.getResumeAt();
    handler.handle(
        instance,
        node,
        execution,
        resolution,
        Duration.ofMillis(1200),
        "duplicate timeout callback",
        false);

    assertThat(execution.getResumeAt()).isEqualTo(scheduledResume);
    verify(nodeExecutionRepository, times(1)).save(execution);
    verify(port, times(1))
        .log(eq(instance), eq("hook"), eq("WEBHOOK"), eq("NODE_TIMED_OUT"), anyString());
  }

  /** The real port transitions the aggregate; the mock has to do the same for state assertions. */
  private void applyTransitionsToTheInstance() {
    doAnswer(
            invocation -> {
              WorkflowInstance target = invocation.getArgument(0);
              WorkflowState state = invocation.getArgument(1);
              target.transitionTo(state);
              return null;
            })
        .when(port)
        .transition(any(), any(), any());
  }

  @Test
  void exhaustedRetryBudgetFailsTheInstanceAndCountsTheFailure() {
    WorkflowInstance instance = instance();
    NodeExecution execution = execution(instance, 3);
    applyTransitionsToTheInstance();
    NodeDefinition node = node(NodeType.WEBHOOK, "{\"maxAttempts\":3}");
    NodeTimeoutResolution resolution =
        NodeTimeoutResolution.supported(NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1));

    handler.handle(instance, node, execution, resolution, Duration.ofSeconds(2), "timeout", false);

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    assertThat(execution.getResumeAt()).isNull();
    assertThat(instance.getState()).isEqualTo(WorkflowState.FAILED);
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_TIMED_OUT");
    assertThat(
            registry
                .get(NodeTimeoutMetrics.TIMEOUT_FAILURES)
                .tag("type", "WEBHOOK")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void aFailedInstanceIsNotTransitionedTwice() {
    WorkflowInstance instance = instance();
    instance.transitionTo(WorkflowState.FAILED);
    NodeExecution execution = execution(instance, 3);
    NodeDefinition node = node(NodeType.WEBHOOK, "{\"maxAttempts\":3}");
    NodeTimeoutResolution resolution =
        NodeTimeoutResolution.supported(NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1));

    handler.handle(instance, node, execution, resolution, Duration.ofSeconds(2), "timeout", false);

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    verify(port, never()).transition(any(), any(), anyString());
  }

  @Test
  void replayModeNeverSchedulesARetry() {
    WorkflowInstance instance = instance();
    NodeExecution execution = execution(instance, 1);
    applyTransitionsToTheInstance();
    NodeDefinition node = node(NodeType.WEBHOOK, "{\"maxAttempts\":5}");
    NodeTimeoutResolution resolution =
        NodeTimeoutResolution.supported(NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(1));

    handler.handle(instance, node, execution, resolution, Duration.ofSeconds(2), "timeout", true);

    assertThat(execution.getResumeAt()).isNull();
    assertThat(instance.getState()).isEqualTo(WorkflowState.FAILED);
  }

  @Test
  void executionLogPayloadCarriesStructuredTimeoutFields() throws Exception {
    WorkflowInstance instance = instance();
    NodeExecution execution = execution(instance, 2);
    NodeDefinition node = node(NodeType.WEBHOOK, "{\"maxAttempts\":4,\"retryBackoffMs\":250}");
    NodeTimeoutResolution resolution =
        NodeTimeoutResolution.supported(NodeTimeoutEnforcement.ENFORCED, Duration.ofSeconds(2));

    handler.handle(
        instance,
        node,
        execution,
        resolution,
        Duration.ofMillis(2345),
        "Node 'hook' exceeded its 2000ms timeout after 2345ms.",
        false);

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(port)
        .log(eq(instance), eq("hook"), eq("WEBHOOK"), eq("NODE_TIMED_OUT"), payload.capture());
    JsonNode json = objectMapper.readTree(payload.getValue());

    assertThat(json.get("enforcement").asText()).isEqualTo("ENFORCED");
    assertThat(json.get("hardGuarantee").asBoolean()).isTrue();
    assertThat(json.get("timeoutSeconds").asLong()).isEqualTo(2);
    assertThat(json.get("elapsedMs").asLong()).isEqualTo(2345);
    assertThat(json.get("attempt").asInt()).isEqualTo(2);
    assertThat(json.get("maxAttempts").asInt()).isEqualTo(4);
    assertThat(json.get("retryScheduled").asBoolean()).isTrue();
    assertThat(json.get("reason").asText()).contains("exceeded its 2000ms timeout");
  }

  @Test
  void databaseTimeoutExceptionsAreClassified() {
    assertThat(
            NodeTimeoutHandler.isTimeoutFailure(
                new org.springframework.dao.QueryTimeoutException("statement timeout")))
        .isTrue();
    assertThat(NodeTimeoutHandler.isTimeoutFailure(new java.sql.SQLTimeoutException("timeout")))
        .isTrue();
    assertThat(
            NodeTimeoutHandler.isTimeoutFailure(
                new jakarta.persistence.QueryTimeoutException("timeout")))
        .isTrue();
    assertThat(
            NodeTimeoutHandler.isTimeoutFailure(
                new RuntimeException(
                    "wrapped", new org.springframework.dao.QueryTimeoutException("inner"))))
        .isTrue();
    assertThat(NodeTimeoutHandler.isTimeoutFailure(new IllegalStateException("boom"))).isFalse();
  }
}
