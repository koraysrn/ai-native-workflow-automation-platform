package com.awap.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DedupePolicyTest {

  @Test
  void workflowTaskApprovalAndFormEventsAreTransactional() {
    assertThat(DedupePolicy.forEventType("WORKFLOW_COMPLETED"))
        .isEqualTo(DedupePolicy.TRANSACTIONAL);
    assertThat(DedupePolicy.forEventType("WORKFLOW_INSTANCE_STARTED"))
        .isEqualTo(DedupePolicy.TRANSACTIONAL);
    assertThat(DedupePolicy.forEventType("TASK_COMPLETED")).isEqualTo(DedupePolicy.TRANSACTIONAL);
    assertThat(DedupePolicy.forEventType("APPROVAL_REQUESTED"))
        .isEqualTo(DedupePolicy.TRANSACTIONAL);
    assertThat(DedupePolicy.forEventType("FORM_SUBMITTED")).isEqualTo(DedupePolicy.TRANSACTIONAL);
  }

  @Test
  void remindersEscalationsAndDigestsArePeriodic() {
    assertThat(DedupePolicy.forEventType("TASK_ESCALATED")).isEqualTo(DedupePolicy.PERIODIC);
    assertThat(DedupePolicy.forEventType("task_reminder")).isEqualTo(DedupePolicy.PERIODIC);
    assertThat(DedupePolicy.forEventType("SLA_BREACH")).isEqualTo(DedupePolicy.PERIODIC);
    assertThat(DedupePolicy.forEventType("SLA_WARNING")).isEqualTo(DedupePolicy.PERIODIC);
    assertThat(DedupePolicy.forEventType("DAILY_DIGEST")).isEqualTo(DedupePolicy.PERIODIC);
    assertThat(DedupePolicy.forEventType("WEEKLY_DIGEST")).isEqualTo(DedupePolicy.PERIODIC);
  }

  @Test
  void unknownBlankAndNullEventTypesFailSafeToTransactional() {
    assertThat(DedupePolicy.forEventType(null)).isEqualTo(DedupePolicy.TRANSACTIONAL);
    assertThat(DedupePolicy.forEventType("  ")).isEqualTo(DedupePolicy.TRANSACTIONAL);
    assertThat(DedupePolicy.forEventType("SOMETHING_NEW")).isEqualTo(DedupePolicy.TRANSACTIONAL);
    assertThat(DedupePolicy.forEventType("NOTIFICATION_REQUESTED"))
        .isEqualTo(DedupePolicy.TRANSACTIONAL);
  }

  @Test
  void transactionalPolicyRequiresABusinessKey() {
    assertThat(DedupePolicy.TRANSACTIONAL.requiresBusinessKey()).isTrue();
    assertThat(DedupePolicy.PERIODIC.requiresBusinessKey()).isFalse();
  }
}
