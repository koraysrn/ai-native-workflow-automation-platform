package com.awap.engine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the workflow instance state machine and its transition guards. */
class WorkflowInstanceTest {

  @Test
  void startsInCreatedState() {
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.CREATED, null, null);

    assertThat(instance.getState()).isEqualTo(WorkflowState.CREATED);
    assertThat(instance.isDeleted()).isFalse();
  }

  @Test
  void terminalStateCannotTransition() {
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.COMPLETED, null, null);

    assertThatThrownBy(() -> instance.transitionTo(WorkflowState.RUNNING))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failedStateOnlyTransitionsToRunning() {
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.FAILED, null, null);

    assertThatThrownBy(() -> instance.transitionTo(WorkflowState.COMPLETED))
        .isInstanceOf(IllegalStateException.class);

    instance.transitionTo(WorkflowState.RUNNING);
    assertThat(instance.getState()).isEqualTo(WorkflowState.RUNNING);
  }

  @Test
  void pauseRequiresRunningOrWaiting() {
    WorkflowInstance running =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.RUNNING, null, null);
    running.pause();
    assertThat(running.getState()).isEqualTo(WorkflowState.PAUSED);

    WorkflowInstance created =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.CREATED, null, null);
    assertThatThrownBy(created::pause).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void updateContextJsonKeepsLatestSnapshot() {
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.WAITING, null, "{}");

    instance.updateContextJson("{\"childDefinitionVersion\":2}");

    assertThat(instance.getContextJson()).contains("\"childDefinitionVersion\":2");
    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);
  }

  @Test
  void resumeRequiresPausedState() {
    WorkflowInstance paused =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.PAUSED, null, null);
    paused.resume();
    assertThat(paused.getState()).isEqualTo(WorkflowState.RUNNING);

    WorkflowInstance running =
        new WorkflowInstance(
            UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.RUNNING, null, null);
    assertThatThrownBy(running::resume).isInstanceOf(IllegalStateException.class);
  }
}
