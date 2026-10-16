package com.awap.task.infrastructure;

import com.awap.task.domain.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for tasks. */
public interface TaskRepository extends JpaRepository<Task, UUID> {

  List<Task> findByTenantIdAndDeletedFalse(UUID tenantId);

  List<Task> findByTenantIdAndAssigneeIdAndDeletedFalse(UUID tenantId, UUID assigneeId);

  Optional<Task> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);
}
