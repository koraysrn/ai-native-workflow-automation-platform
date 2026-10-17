package com.awap.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.awap.common.api.AwapException;
import com.awap.engine.application.OutboxEventPublisher;
import com.awap.task.domain.TaskStatus;
import com.awap.task.infrastructure.TaskRepository;
import com.awap.task.interfaces.dto.TaskRequest;
import com.awap.task.interfaces.dto.TaskResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
  @Mock private MeterRegistry meterRegistry;
  @Mock private Counter counter;

  private TaskService service;

  @BeforeEach
  void setUp() {
    lenient().when(meterRegistry.counter(anyString(), anyString(), anyString())).thenReturn(counter);
    service = new TaskService(taskRepository, outboxEventPublisher, meterRegistry);
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

    assertThatThrownBy(() -> service.approve(tenantId, id, UUID.randomUUID(), "EMPLOYEE"))
        .isInstanceOf(AwapException.class);
  }
}
