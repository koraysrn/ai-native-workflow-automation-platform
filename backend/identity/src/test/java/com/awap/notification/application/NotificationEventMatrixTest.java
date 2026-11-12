package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Every task/workflow event from issue #203 has a documented template, with a noisy fallback. */
class NotificationEventMatrixTest {

  private final NotificationEventMatrix matrix = new NotificationEventMatrix();

  @Test
  void mapsEveryTaskAndWorkflowEventToItsTemplate() {
    assertThat(matrix.templateKeyFor("TASK_ASSIGNED")).isEqualTo("task-assigned");
    assertThat(matrix.templateKeyFor("TASK_APPROVED")).isEqualTo("task-approved");
    assertThat(matrix.templateKeyFor("TASK_REJECTED")).isEqualTo("task-rejected");
    assertThat(matrix.templateKeyFor("TASK_COMPLETED")).isEqualTo("task-completed");
    assertThat(matrix.templateKeyFor("TASK_ESCALATED")).isEqualTo("task-escalated");
    assertThat(matrix.templateKeyFor("WORKFLOW_STARTED")).isEqualTo("workflow-started");
    assertThat(matrix.templateKeyFor("WORKFLOW_COMPLETED")).isEqualTo("workflow-completed");
    assertThat(matrix.templateKeyFor("WORKFLOW_FAILED")).isEqualTo("workflow-failed");
    assertThat(matrix.templateKeyFor("WORKFLOW_PAUSED")).isEqualTo("workflow-paused");
    assertThat(matrix.templateKeyFor("WORKFLOW_RESUMED")).isEqualTo("workflow-resumed");
    assertThat(matrix.mappedEventTypes()).hasSize(10);
  }

  @Test
  void normalizesCaseAndWhitespace() {
    assertThat(matrix.templateKeyFor("  task_assigned ")).isEqualTo("task-assigned");
    assertThat(matrix.isMapped(" Workflow_Completed ")).isTrue();
  }

  @Test
  void fallsBackToTheGenericTemplateForAnUnmappedEvent() {
    assertThat(matrix.templateKeyFor("TASK_REMINDER"))
        .isEqualTo(NotificationEventMatrix.FALLBACK_TEMPLATE);
    assertThat(matrix.isMapped("TASK_REMINDER")).isFalse();
    assertThat(matrix.templateKeyFor(null)).isEqualTo("notification");
    assertThat(matrix.isMapped("")).isFalse();
  }
}
