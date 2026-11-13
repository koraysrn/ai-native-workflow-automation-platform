package com.awap.task.application;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.engine.domain.WorkflowState;
import com.awap.engine.infrastructure.WorkflowInstanceRepository;
import com.awap.identity.domain.Role;
import com.awap.identity.domain.User;
import com.awap.identity.infrastructure.UserRepository;
import com.awap.task.domain.Task;
import com.awap.task.domain.TaskStatus;
import com.awap.task.infrastructure.TaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a due task escalation exactly once (issue #207).
 *
 * <p>The dedicated consumer calls this service instead of the node-completion listener. The service
 * is idempotent: {@link Task#isEscalationApplied()} is a durable guard, so a duplicate Kafka record
 * cannot reassign the task or emit a second notification. Paused workflows and already terminal
 * tasks are skipped, and a missing configured manager falls back to the tenant admin before the
 * escalation is abandoned with a visible metric.
 */
@Service
public class TaskEscalationService {

  static final String HANDLED_METRIC = "awap.task.escalation.handled";
  static final String SKIPPED_METRIC = "awap.task.escalation.skipped";
  static final String FALLBACK_METRIC = "awap.task.escalation.fallback";

  private static final Logger log = LoggerFactory.getLogger(TaskEscalationService.class);

  private static final Set<TaskStatus> TERMINAL_STATUSES =
      EnumSet.of(
          TaskStatus.REJECTED, TaskStatus.APPROVED, TaskStatus.COMPLETED, TaskStatus.CANCELLED);

  private final TaskRepository taskRepository;
  private final WorkflowInstanceRepository workflowInstanceRepository;
  private final UserRepository userRepository;
  private final OutboxEventPublisher outboxEventPublisher;
  private final MeterRegistry meterRegistry;
  private final Clock clock;

  @Autowired
  public TaskEscalationService(
      TaskRepository taskRepository,
      WorkflowInstanceRepository workflowInstanceRepository,
      UserRepository userRepository,
      OutboxEventPublisher outboxEventPublisher,
      MeterRegistry meterRegistry) {
    this(
        taskRepository,
        workflowInstanceRepository,
        userRepository,
        outboxEventPublisher,
        meterRegistry,
        Clock.systemUTC());
  }

  TaskEscalationService(
      TaskRepository taskRepository,
      WorkflowInstanceRepository workflowInstanceRepository,
      UserRepository userRepository,
      OutboxEventPublisher outboxEventPublisher,
      MeterRegistry meterRegistry,
      Clock clock) {
    this.taskRepository = taskRepository;
    this.workflowInstanceRepository = workflowInstanceRepository;
    this.userRepository = userRepository;
    this.outboxEventPublisher = outboxEventPublisher;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
  }

  /** Reassigns the task to its escalation target and emits the notification lifecycle fact. */
  @Transactional
  public void escalate(TaskEscalationDue event) {
    Optional<Task> found =
        taskRepository.findByIdAndTenantIdAndDeletedFalse(event.taskId(), event.tenantId());
    if (found.isEmpty()) {
      skip("missing_task");
      return;
    }
    Task task = found.get();
    if (TERMINAL_STATUSES.contains(task.getStatus())) {
      skip("terminal");
      return;
    }
    if (task.isEscalationApplied()) {
      skip("duplicate");
      return;
    }
    if (isPaused(task)) {
      skip("paused");
      return;
    }

    UUID target = resolveTarget(event);
    if (target == null) {
      skip("missing_target");
      return;
    }

    task.reassign(target);
    task.markEscalationApplied(clock.instant());

    // The outbox write and the in-process fan-out are one transaction: the notification producer
    // (notification module) turns this lifecycle fact into a NOTIFICATION_REQUESTED record, so the
    // escalation and its notification commit together.
    outboxEventPublisher.publish(
        task.getTenantId(),
        "TASK",
        task.getId(),
        "TASK_ESCALATED",
        new TaskLifecycleEvent(
            task.getTenantId(),
            task.getId(),
            "TASK_ESCALATED",
            task.getTitle(),
            target,
            task.getCreatedBy(),
            null,
            task.getWorkflowInstanceId()));

    counter(HANDLED_METRIC).increment();
    log.info(
        "Task {} escalated to user {} for tenant {}.", task.getId(), target, task.getTenantId());
  }

  private UUID resolveTarget(TaskEscalationDue event) {
    if (event.assigneeId() != null
        && userRepository.findByIdAndTenantId(event.assigneeId(), event.tenantId()).isPresent()) {
      return event.assigneeId();
    }
    Optional<UUID> manager =
        userRepository.findFirstByTenantIdAndRole(event.tenantId(), Role.MANAGER).map(User::getId);
    if (manager.isPresent()) {
      Counter.builder(FALLBACK_METRIC).tag("source", "manager").register(meterRegistry).increment();
      return manager.get();
    }
    Optional<UUID> tenantAdmin =
        userRepository
            .findFirstByTenantIdAndRole(event.tenantId(), Role.TENANT_ADMIN)
            .map(User::getId);
    if (tenantAdmin.isPresent()) {
      Counter.builder(FALLBACK_METRIC)
          .tag("source", "tenant_admin")
          .register(meterRegistry)
          .increment();
      return tenantAdmin.get();
    }
    return null;
  }

  private boolean isPaused(Task task) {
    if (task.getWorkflowInstanceId() == null) {
      return false;
    }
    return workflowInstanceRepository
        .findByIdAndTenantIdAndDeletedFalse(task.getWorkflowInstanceId(), task.getTenantId())
        .map(instance -> instance.getState() == WorkflowState.PAUSED)
        .orElse(false);
  }

  private void skip(String reason) {
    Counter.builder(SKIPPED_METRIC).tag("reason", reason).register(meterRegistry).increment();
    log.info("Task escalation skipped ({}).", reason);
  }

  private Counter counter(String name) {
    return Counter.builder(name).register(meterRegistry);
  }
}
