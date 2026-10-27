package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.awap.common.api.AwapException;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.application.TaskService;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.workflow.application.WorkflowDefinitionService;
import com.awap.workflow.domain.NodeDefinition;
import com.awap.workflow.domain.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/** Engine-level node timeout tests with real durations and real watchdog instances. */
@ExtendWith(MockitoExtension.class)
class NodeExecutorTimeoutTest {

  @Mock NodeExecutionRepository nodeExecutionRepository;
  @Mock WorkflowDefinitionService workflowDefinitionService;
  @Mock TaskService taskService;
  @Mock UserRepository userRepository;
  @Mock WorkflowEnginePort port;
  @Mock WebhookInvoker webhookInvoker;

  private final UUID tenantId = UUID.randomUUID();
  private final UUID workflowDefinitionId = UUID.randomUUID();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private SimpleMeterRegistry registry;
  private NodeExecutionWatchdog watchdog;
  private NodeExecutor executor;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    NodeTimeoutMetrics metrics = new NodeTimeoutMetrics(registry);
    NodeTimeoutSettings settings = new NodeTimeoutSettings(30, 3600, 2, 8);
    watchdog =
        new NodeExecutionWatchdog(
            settings, metrics, Duration.ofMillis(100), Duration.ofMillis(100));
    NodeConfigReader configReader = new NodeConfigReader(objectMapper);
    NodeTimeoutGuard guard =
        new NodeTimeoutGuard(
            settings, watchdog, metrics, nodeExecutionRepository, configReader, port, objectMapper);
    executor =
        new NodeExecutor(
            nodeExecutionRepository,
            workflowDefinitionService,
            configReader,
            taskService,
            userRepository,
            port,
            webhookInvoker,
            guard);
  }

  @AfterEach
  void tearDown() {
    watchdog.shutdown();
    Thread.interrupted();
  }

  private WorkflowInstance instance(WorkflowState state) {
    return new WorkflowInstance(tenantId, workflowDefinitionId, 1, state, null, "{}");
  }

  private NodeDefinition node(String nodeId, NodeType type, String configJson) {
    return new NodeDefinition(tenantId, UUID.randomUUID(), nodeId, type, nodeId, configJson, 0);
  }

  private void stubFreshExecution() {
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(any(), anyString()))
        .thenReturn(Optional.empty());
    when(nodeExecutionRepository.save(any(NodeExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
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

  private NodeExecution lastSavedExecution() {
    ArgumentCaptor<NodeExecution> captor = ArgumentCaptor.forClass(NodeExecution.class);
    verify(nodeExecutionRepository, atLeastOnce()).save(captor.capture());
    List<NodeExecution> values = captor.getAllValues();
    return values.getLast();
  }

  @Test
  void blockingWebhookIsMarkedTimedOutWithinTolerance() throws Exception {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    applyTransitionsToTheInstance();
    CountDownLatch release = new CountDownLatch(1);
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              release.await(5, TimeUnit.SECONDS);
              return "{}";
            });

    long start = System.nanoTime();
    executor.executeNode(
        instance,
        node(
            "hook",
            NodeType.WEBHOOK,
            "{\"url\":\"https://hooks.example.com\",\"timeoutSeconds\":1,\"maxAttempts\":1}"));
    Duration wallClock = Duration.ofNanos(System.nanoTime() - start);
    release.countDown();

    assertThat(wallClock).isBetween(Duration.ofSeconds(1), Duration.ofMillis(1500));
    NodeExecution execution = lastSavedExecution();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    assertThat(execution.getError()).contains("exceeded its 1000ms timeout");
    assertThat(execution.getResumeAt()).isNull();
    verify(port).log(eq(instance), eq("hook"), eq("WEBHOOK"), eq("NODE_TIMED_OUT"), anyString());
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_TIMED_OUT");
    verify(port, never()).advanceFrom(any(), anyString());
    assertThat(instance.getState()).isEqualTo(WorkflowState.FAILED);
    assertThat(
            registry
                .get(NodeTimeoutMetrics.TIMEOUT)
                .tag("type", "WEBHOOK")
                .tag("reason", "enforced")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void fastWebhookCompletesAndRecordsTheDuration() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    when(webhookInvoker.invoke(any(), any(), any())).thenReturn("{\"ok\":true}");

    executor.executeNode(
        instance,
        node(
            "hook",
            NodeType.WEBHOOK,
            "{\"url\":\"https://hooks.example.com\",\"timeoutSeconds\":1}"));

    NodeExecution execution = lastSavedExecution();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(execution.getOutputJson()).isEqualTo("{\"ok\":true}");
    verify(port).advanceFrom(instance, "hook");
    assertThat(registry.find(NodeTimeoutMetrics.TIMEOUT).counter()).isNull();
    assertThat(registry.get(NodeTimeoutMetrics.DURATION).tag("type", "WEBHOOK").timer().count())
        .isEqualTo(1L);
  }

  @Test
  void interruptFlagIsClearedAndTheNextNodeExecutesNormally() throws Exception {
    WorkflowInstance timedOut = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    applyTransitionsToTheInstance();
    CountDownLatch release = new CountDownLatch(1);
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              release.await(5, TimeUnit.SECONDS);
              return "{}";
            });

    executor.executeNode(
        timedOut,
        node(
            "hook",
            NodeType.WEBHOOK,
            "{\"url\":\"https://hooks.example.com\",\"timeoutSeconds\":1,\"maxAttempts\":1}"));
    release.countDown();

    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    assertThat(timedOut.getState()).isEqualTo(WorkflowState.FAILED);

    WorkflowInstance next = instance(WorkflowState.STARTED);
    executor.executeNode(next, node("start", NodeType.START, null));

    assertThat(next.getState()).isEqualTo(WorkflowState.RUNNING);
    verify(port).advanceFrom(next, "start");
  }

  @Test
  void retryableTimeoutIsRetriedUpToMaxAttemptsWithoutDuplicatingSideEffects() throws Exception {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    applyTransitionsToTheInstance();
    AtomicReference<NodeExecution> stored = new AtomicReference<>();
    when(nodeExecutionRepository.findByWorkflowInstanceIdAndNodeId(any(), anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(nodeExecutionRepository.save(any(NodeExecution.class)))
        .thenAnswer(
            invocation -> {
              NodeExecution saved = invocation.getArgument(0);
              stored.set(saved);
              return saved;
            });
    Set<String> sideEffectKeys = ConcurrentHashMap.newKeySet();
    CountDownLatch release = new CountDownLatch(1);
    when(webhookInvoker.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              WorkflowInstance target = invocation.getArgument(0);
              NodeDefinition definition = invocation.getArgument(1);
              NodeExecution execution = invocation.getArgument(2);
              sideEffectKeys.add(
                  target.getId() + ":" + definition.getNodeId() + ":" + execution.getId());
              release.await(2, TimeUnit.SECONDS);
              return "{}";
            });
    NodeDefinition hook =
        node(
            "hook",
            NodeType.WEBHOOK,
            "{\"url\":\"https://hooks.example.com\",\"timeoutSeconds\":1,"
                + "\"maxAttempts\":2,\"retryBackoffMs\":1}");

    executor.executeNode(instance, hook);

    NodeExecution firstAttempt = stored.get();
    assertThat(firstAttempt.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    assertThat(firstAttempt.getResumeAt()).isNotNull();
    assertThat(instance.getState()).isEqualTo(WorkflowState.RUNNING);
    verify(webhookInvoker, times(1)).invoke(any(), any(), any());

    firstAttempt.resetForRetry();
    executor.executeNode(instance, hook);
    release.countDown();

    NodeExecution secondAttempt = stored.get();
    assertThat(secondAttempt.getId()).isEqualTo(firstAttempt.getId());
    assertThat(secondAttempt.getAttempt()).isEqualTo(2);
    assertThat(secondAttempt.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    assertThat(secondAttempt.getResumeAt()).isNull();
    assertThat(instance.getState()).isEqualTo(WorkflowState.FAILED);
    verify(webhookInvoker, times(2)).invoke(any(), any(), any());
    assertThat(sideEffectKeys).hasSize(1);
    assertThat(sideEffectKeys.iterator().next())
        .isEqualTo(instance.getId() + ":hook:" + firstAttempt.getId());
    assertThat(
            registry
                .get(NodeTimeoutMetrics.TIMEOUT_FAILURES)
                .tag("type", "WEBHOOK")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void unsupportedNodeTypeIgnoresTheTimeoutConfigurationAndWarns() {
    WorkflowInstance instance = instance(WorkflowState.STARTED);
    stubFreshExecution();
    Logger guardLogger = (Logger) LoggerFactory.getLogger(NodeTimeoutGuard.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    guardLogger.addAppender(appender);
    try {
      executor.executeNode(instance, node("start", NodeType.START, "{\"timeoutSeconds\":1}"));
    } finally {
      guardLogger.detachAppender(appender);
    }

    NodeExecution execution = lastSavedExecution();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    verify(port).advanceFrom(instance, "start");
    assertThat(appender.list).isNotEmpty();
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("Node timeout configuration ignored");
              assertThat(event.getKeyValuePairs()).isNotNull();
              assertThat(event.getKeyValuePairs())
                  .anySatisfy(pair -> assertThat(pair.key).isEqualTo("nodeType"));
              assertThat(event.getKeyValuePairs())
                  .anySatisfy(
                      pair -> {
                        assertThat(pair.key).isEqualTo("configuredTimeoutSeconds");
                        assertThat(pair.value).isEqualTo(1L);
                      });
            });
  }

  @Test
  void bestEffortNodeBreachingItsBudgetIsMarkedTimedOutAfterTheWorkReturns() throws Exception {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    applyTransitionsToTheInstance();
    when(taskService.create(any(), any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(1200);
              return null;
            });

    executor.executeNode(
        instance, node("approve", NodeType.TASK, "{\"timeoutSeconds\":1,\"maxAttempts\":1}"));

    verify(taskService).create(eq(tenantId), any(TaskRequest.class));
    NodeExecution execution = lastSavedExecution();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    assertThat(instance.getState()).isEqualTo(WorkflowState.FAILED);
    assertThat(
            registry
                .get(NodeTimeoutMetrics.TIMEOUT)
                .tag("type", "TASK")
                .tag("reason", "best_effort")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void zeroTimeoutSecondsIsRejectedByValidation() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    NodeDefinition hook =
        node(
            "hook",
            NodeType.WEBHOOK,
            "{\"url\":\"https://hooks.example.com\",\"timeoutSeconds\":0}");

    assertThatThrownBy(() -> executor.executeNode(instance, hook))
        .isInstanceOf(AwapException.class)
        .hasMessageContaining("timeoutSeconds=0");
    verify(webhookInvoker, never()).invoke(any(), any(), any());
  }

  @Test
  void databaseTimeoutIsClassifiedAsNodeTimeout() {
    WorkflowInstance instance = instance(WorkflowState.RUNNING);
    stubFreshExecution();
    when(taskService.create(any(), any()))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("statement timeout"));

    executor.executeNode(instance, node("approve", NodeType.TASK, "{\"maxAttempts\":1}"));

    NodeExecution execution = lastSavedExecution();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.TIMED_OUT);
    assertThat(execution.getError()).contains("Database operation timed out");
    verify(port).log(eq(instance), eq("approve"), eq("TASK"), eq("NODE_TIMED_OUT"), anyString());
    verify(port).transition(instance, WorkflowState.FAILED, "NODE_TIMED_OUT");
    assertThat(
            registry
                .get(NodeTimeoutMetrics.TIMEOUT)
                .tag("type", "TASK")
                .tag("reason", "database")
                .counter()
                .count())
        .isEqualTo(1.0d);
  }
}
