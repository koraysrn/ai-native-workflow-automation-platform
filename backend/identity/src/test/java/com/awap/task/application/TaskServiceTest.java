package com.awap.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.task.domain.Task;
import com.awap.task.domain.TaskStatus;
import com.awap.task.infrastructure.TaskRepository;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.task.interfaces.dto.TaskResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

  @Mock private TaskRepository taskRepository;
  @Mock private OutboxEventPublisher outboxEventPublisher;

  private TaskService service;

  @BeforeEach
  void setUp() {
    service = new TaskService(taskRepository, outboxEventPublisher);
  }

  @Test
  void createReturnsOpenTask() {
    TaskRequest request = new TaskRequest("Review", null, null, null, null);

    TaskResponse response = service.create(UUID.randomUUID(), request);

    assertThat(response.title()).isEqualTo("Review");
    assertThat(response.status()).isEqualTo(TaskStatus.OPEN.name());
  }

  @Test
  void approveMissingTaskThrows() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(id, tenantId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.approve(tenantId, id, UUID.randomUUID(), "EMPLOYEE", null))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void approvePersistsComment() {
    UUID tenantId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID assigneeId = UUID.randomUUID();
    Task task = new Task(tenantId, null, null, "Review", null, assigneeId);
    task.claim(assigneeId);
    when(taskRepository.findByIdAndTenantIdAndDeletedFalse(id, tenantId))
        .thenReturn(Optional.of(task));

    TaskResponse response = service.approve(tenantId, id, assigneeId, "EMPLOYEE", "Looks good");

    assertThat(response.status()).isEqualTo(TaskStatus.APPROVED.name());
    assertThat(response.decisionComment()).isEqualTo("Looks good");
  }
}
