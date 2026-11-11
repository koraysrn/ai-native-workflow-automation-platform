package com.awap.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.task.domain.Task;
import com.awap.task.infrastructure.TaskRepository;
import com.awap.task.interfaces.dto.TaskAssignRequest;
import com.awap.task.interfaces.dto.TaskMapper;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.usage.application.UsageMeteringService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Task transitions emit the lifecycle facts the notification producer consumes (issue #203). */
class TaskLifecycleEventPublishingTest {

  private final TaskRepository taskRepository = mock(TaskRepository.class);
  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final TaskMapper taskMapper = mock(TaskMapper.class);
  private final UsageMeteringService usageMeteringService = mock(UsageMeteringService.class);
  private final TaskService service =
      new TaskService(
          taskRepository, outbox, new SimpleMeterRegistry(), taskMapper, usageMeteringService);

  private final UUID tenantId = UUID.randomUUID();
  private final UUID assigneeId = UUID.randomUUID();

  private Task storedTask() {
    Task task = new Task(tenantId, null, null, "Approve invoice", null, assigneeId);
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(eq(task.getId()), eq(tenantId)))
        .thenReturn(Optional.of(task));
    return task;
  }

  private TaskLifecycleEvent captured(String eventType) {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<TaskLifecycleEvent> captor = ArgumentCaptor.forClass(TaskLifecycleEvent.class);
    verify(outbox).publish(eq(tenantId), eq("TASK"), any(), eq(eventType), captor.capture());
    return captor.getValue();
  }

  @BeforeEach
  void mapAnyTask() {
    when(taskMapper.toResponse(any())).thenReturn(null);
  }

  @Test
  void assignmentEmitsTaskAssignedWithTheAssignee() {
    Task task = storedTask();
    service.assign(tenantId, task.getId(), new TaskAssignRequest(assigneeId));

    TaskLifecycleEvent event = captured("TASK_ASSIGNED");
    assertThat(event.taskId()).isEqualTo(task.getId());
    assertThat(event.assigneeId()).isEqualTo(assigneeId);
    assertThat(event.title()).isEqualTo("Approve invoice");
  }

  @Test
  void decisionsAndCompletionEmitTheirOwnEvents() {
    Task approved = storedTask();
    service.approve(tenantId, approved.getId(), UUID.randomUUID(), "TENANT_ADMIN", "ok");
    assertThat(captured("TASK_APPROVED").taskId()).isEqualTo(approved.getId());

    Task rejected = storedTask();
    service.reject(tenantId, rejected.getId(), UUID.randomUUID(), "TENANT_ADMIN", "no");
    assertThat(captured("TASK_REJECTED").taskId()).isEqualTo(rejected.getId());

    Task completed = storedTask();
    service.complete(tenantId, completed.getId());
    assertThat(captured("TASK_COMPLETED").taskId()).isEqualTo(completed.getId());

    Task escalated = storedTask();
    service.escalate(tenantId, escalated.getId(), new TaskAssignRequest(UUID.randomUUID()));
    assertThat(captured("TASK_ESCALATED").taskId()).isEqualTo(escalated.getId());
  }

  @Test
  void creationWithAnAssigneeEmitsTaskAssignedAndWithoutOneEmitsNothing() {
    UUID unassignedTenant = UUID.randomUUID();
    service.create(unassignedTenant, new TaskRequest("Nobody yet", null, null, null, null));
    verify(outbox, never())
        .publish(eq(unassignedTenant), eq("TASK"), any(), eq("TASK_ASSIGNED"), any());

    UUID assignedTenant = UUID.randomUUID();
    when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
    service.create(
        assignedTenant, new TaskRequest("Approve invoice", null, null, null, assigneeId));
    verify(outbox).publish(eq(assignedTenant), eq("TASK"), any(), eq("TASK_ASSIGNED"), any());
  }
}
