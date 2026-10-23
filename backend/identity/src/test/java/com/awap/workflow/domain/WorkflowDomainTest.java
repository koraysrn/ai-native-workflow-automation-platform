package com.awap.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.domain.ExecutionLog;
import com.awap.engine.domain.OutboxEvent;
import com.awap.form.domain.FormDefinition;
import com.awap.form.domain.FormSubmission;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies workflow/form/outbox domain invariants. */
class WorkflowDomainTest {

  @Test
  void workflowDefinitionPublishesAndRollsBack() {
    WorkflowDefinition definition = new WorkflowDefinition(UUID.randomUUID(), "Expense", "desc");
    assertThat(definition.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
    assertThat(definition.getCurrentVersion()).isZero();

    definition.publish(3);
    assertThat(definition.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);
    assertThat(definition.getCurrentVersion()).isEqualTo(3);

    definition.rollbackTo(2);
    assertThat(definition.getCurrentVersion()).isEqualTo(2);
  }

  @Test
  void workflowVersionPublishesAndArchives() {
    WorkflowVersion version =
        new WorkflowVersion(UUID.randomUUID(), UUID.randomUUID(), 1, "{}", WorkflowStatus.DRAFT);

    version.publish();
    assertThat(version.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);

    version.archive();
    assertThat(version.isDeleted()).isTrue();
  }

  @Test
  void formDefinitionVersionIncrementsOnUpdate() {
    FormDefinition form = new FormDefinition(UUID.randomUUID(), "Leave", "{}");
    assertThat(form.getVersion()).isEqualTo(1);

    form.updateSchema("Leave v2", "{\"fields\":[]}");

    assertThat(form.getVersion()).isEqualTo(2);
    assertThat(form.getName()).isEqualTo("Leave v2");
  }

  @Test
  void formSubmissionCarriesSubmitter() {
    UUID submittedBy = UUID.randomUUID();
    FormSubmission submission =
        new FormSubmission(UUID.randomUUID(), UUID.randomUUID(), null, "{}", submittedBy);

    assertThat(submission.getSubmittedBy()).isEqualTo(submittedBy);
    assertThat(submission.getCreatedAt()).isNotNull();
  }

  @Test
  void outboxEventStartsUnpublishedAndIsMarkable() {
    OutboxEvent event =
        new OutboxEvent(UUID.randomUUID(), "TASK", UUID.randomUUID(), "TASK_COMPLETED", "{}");

    assertThat(event.isPublished()).isFalse();

    event.markPublished();
    assertThat(event.isPublished()).isTrue();
  }

  @Test
  void executionLogCapturesAuditFields() {
    ExecutionLog log =
        new ExecutionLog(
            UUID.randomUUID(), UUID.randomUUID(), "n1", "TASK", "NODE_COMPLETED", "payload");

    assertThat(log.getAction()).isEqualTo("NODE_COMPLETED");
    assertThat(log.getNodeId()).isEqualTo("n1");
    assertThat(log.getCreatedAt()).isNotNull();
  }
}
