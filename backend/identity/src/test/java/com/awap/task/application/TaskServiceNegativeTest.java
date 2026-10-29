package com.awap.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.task.domain.Task;
import com.awap.task.infrastructure.TaskRepository;
import com.awap.task.interfaces.dto.TaskAssignRequest;
import com.awap.task.interfaces.dto.TaskMapperImpl;
import com.awap.usage.application.UsageMeteringService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** Task negative paths: terminal-state guards and unauthorized decision rights. */
@ExtendWith(MockitoExtension.class)
class TaskServiceNegativeTest {

  @Mock private TaskRepository taskRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;
  @Mock private MeterRegistry meterRegistry;
  @Mock private UsageMeteringService usageMeteringService;

  private TaskService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskService(
            taskRepository,
            outboxEventPublisher,
            meterRegistry,
            new TaskMapperImpl(),
            usageMeteringService);
  }

  private Task completedTask(UUID tenantId, UUID assigneeId) {
    Task task = new Task(tenantId, null, null, "Review", null, assigneeId);
    task.complete();
    return task;
  }

  private void givenTask(UUID tenantId, UUID id, Task task) {
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(id, tenantId))
        .thenReturn(Optional.of(task));
  }

  @Test
  void rejectByNonAssigneeEmployeeIsForbidden() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    givenTask(tenantId, id, new Task(tenantId, null, null, "Review", null, assignee));

    assertThatThrownBy(() -> service.reject(tenantId, id, other, "EMPLOYEE", "nope"))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void approveByNonAssigneeEmployeeIsForbidden() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    givenTask(tenantId, id, new Task(tenantId, null, null, "Review", null, assignee));

    assertThatThrownBy(() -> service.approve(tenantId, id, other, "EMPLOYEE", "ok"))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void approveOnCompletedTaskIsConflict() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    givenTask(tenantId, id, completedTask(tenantId, actor));

    assertThatThrownBy(() -> service.approve(tenantId, id, actor, "EMPLOYEE", "late"))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void reassignOnCompletedTaskIsConflict() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    givenTask(tenantId, id, completedTask(tenantId, actor));

    assertThatThrownBy(
            () -> service.reassign(tenantId, id, new TaskAssignRequest(UUID.randomUUID())))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void claimByWrongUserIsConflict() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    givenTask(tenantId, id, new Task(tenantId, null, null, "Review", null, assignee));

    assertThatThrownBy(() -> service.claim(tenantId, id, other))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void escalateOnCompletedTaskIsConflict() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    givenTask(tenantId, id, completedTask(tenantId, actor));

    assertThatThrownBy(
            () -> service.escalate(tenantId, id, new TaskAssignRequest(UUID.randomUUID())))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void assignOnApprovedTaskIsConflict() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    Task task = new Task(tenantId, null, null, "Review", null, assignee);
    task.approve("ok");
    givenTask(tenantId, id, task);

    assertThatThrownBy(() -> service.assign(tenantId, id, new TaskAssignRequest(UUID.randomUUID())))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void missingTaskReturnsNotFound() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(id, tenantId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.complete(tenantId, id))
        .isInstanceOfSatisfying(
            AwapException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }
}
