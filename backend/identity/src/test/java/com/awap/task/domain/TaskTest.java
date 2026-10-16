package com.awap.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTest {

  private final UUID tenantId = UUID.randomUUID();
  private final UUID userA = UUID.randomUUID();
  private final UUID userB = UUID.randomUUID();

  @Test
  void claimAndCompleteHappyPath() {
    Task task = new Task(tenantId, null, null, "Review", null, null);
    task.claim(userA);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    task.complete();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(task.getCompletedAt()).isNotNull();
  }

  @Test
  void approveThenApproveAgainIsRejected() {
    Task task = new Task(tenantId, null, null, "Approve", null, userA);
    task.claim(userA);
    task.approve();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);
    assertThatThrownBy(task::approve).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void assignedTaskCannotBeClaimedByAnotherUser() {
    Task task = new Task(tenantId, null, null, "Task", null, userA);
    assertThatThrownBy(() -> task.claim(userB)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reassignMovesTaskToAnotherUser() {
    Task task = new Task(tenantId, null, null, "Task", null, userA);
    task.reassign(userB);
    assertThat(task.getAssigneeId()).isEqualTo(userB);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
  }

  @Test
  void escalateSetsDeadlineAndAssignee() {
    Task task = new Task(tenantId, null, null, "Task", null, null);
    task.escalate(userA, java.time.Instant.now().plusSeconds(60));
    assertThat(task.isEscalated()).isTrue();
    assertThat(task.getAssigneeId()).isEqualTo(userA);
  }
}
