package com.awap.engine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the node execution state machine including delay and parallel-branch bookkeeping. */
class NodeExecutionTest {

  @Test
  void startsWithPendingStatusAndZeroAttempts() {
    NodeExecution execution = new NodeExecution(UUID.randomUUID(), UUID.randomUUID(), "n1", "TASK");

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.PENDING);
    assertThat(execution.getAttempt()).isZero();
  }

  @Test
  void startIncrementsAttemptAndMovesToRunning() {
    NodeExecution execution = new NodeExecution(UUID.randomUUID(), UUID.randomUUID(), "n1", "TASK");

    execution.start();
    execution.start();

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.RUNNING);
    assertThat(execution.getAttempt()).isEqualTo(2);
  }

  @Test
  void completeRecordsOutputAndTimestamp() {
    NodeExecution execution = new NodeExecution(UUID.randomUUID(), UUID.randomUUID(), "n1", "TASK");
    execution.start();
    execution.complete("{\"ok\":true}");

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(execution.getOutputJson()).isEqualTo("{\"ok\":true}");
    assertThat(execution.getCompletedAt()).isNotNull();
  }

  @Test
  void failRecordsErrorAndTimestamp() {
    NodeExecution execution = new NodeExecution(UUID.randomUUID(), UUID.randomUUID(), "n1", "TASK");
    execution.fail("boom");

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(execution.getError()).isEqualTo("boom");
  }

  @Test
  void delayResumeIsScheduledAndClearable() {
    NodeExecution execution =
        new NodeExecution(UUID.randomUUID(), UUID.randomUUID(), "n1", "DELAY");
    Instant resumeAt = Instant.now().plusSeconds(30);

    execution.scheduleResume(resumeAt);
    assertThat(execution.getResumeAt()).isEqualTo(resumeAt);

    execution.clearResumeAt();
    assertThat(execution.getResumeAt()).isNull();
  }

  @Test
  void parallelBranchesTrackMergeCount() {
    NodeExecution execution =
        new NodeExecution(UUID.randomUUID(), UUID.randomUUID(), "merge", "MERGE");

    execution.openBranches(3);
    assertThat(execution.getBranchTokenCount()).isEqualTo(3);
    assertThat(execution.getMergedBranchCount()).isZero();

    execution.recordBranchMerge();
    execution.recordBranchMerge();
    assertThat(execution.getMergedBranchCount()).isEqualTo(2);

    execution.setJoinNodeId("join-1");
    assertThat(execution.getJoinNodeId()).isEqualTo("join-1");
  }
}
