package com.awap.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.identity.IdentityApplication;
import com.awap.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = IdentityApplication.class)
class TaskRepositoryITest {

  @Autowired private TaskRepository taskRepository;

  @Test
  void findByTenantReturnsOnlyThatTenantsTasks() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    taskRepository.save(new Task(tenantA, null, null, "A1", null, null));
    taskRepository.save(new Task(tenantB, null, null, "B1", null, null));

    assertThat(taskRepository.findByTenantIdAndDeletedFalse(tenantA)).hasSize(1);
    assertThat(taskRepository.findByTenantIdAndDeletedFalse(tenantB)).hasSize(1);
  }

  @Test
  void findByIdAndTenantReturnsEmptyForOtherTenant() {
    UUID tenantA = UUID.randomUUID();
    Task task = taskRepository.save(new Task(tenantA, null, null, "T", null, null));

    assertThat(taskRepository.findByIdAndTenantIdAndDeletedFalse(task.getId(), UUID.randomUUID()))
        .isEmpty();
  }
}
