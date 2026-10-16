package com.awap.engine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Locks the deterministic state-machine rule: same event sequence always yields the same state. */
class WorkflowStateMachineDeterminismTest {

  @Test
  void sameTransitionSequenceAlwaysYieldsSameState() {
    WorkflowInstance first = newInstance();
    WorkflowInstance second = newInstance();

    WorkflowState[] sequence = {
      WorkflowState.STARTED,
      WorkflowState.RUNNING,
      WorkflowState.WAITING,
      WorkflowState.RUNNING,
      WorkflowState.COMPLETED
    };
    for (WorkflowState state : sequence) {
      first.transitionTo(state);
      second.transitionTo(state);
    }

    assertThat(first.getState()).isEqualTo(second.getState());
    assertThat(first.getState()).isEqualTo(WorkflowState.COMPLETED);
  }

  @Test
  void terminalStateCannotTransitionDeterministically() {
    WorkflowInstance completed = new WorkflowInstance(
        UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.COMPLETED, null, "{}");

    assertThat(completed.getState()).isEqualTo(WorkflowState.COMPLETED);
    Assertions.assertThrows(
        IllegalStateException.class, () -> completed.transitionTo(WorkflowState.RUNNING));
  }

  private WorkflowInstance newInstance() {
    return new WorkflowInstance(
        UUID.randomUUID(), UUID.randomUUID(), 1, WorkflowState.CREATED, null, "{}");
  }
}
