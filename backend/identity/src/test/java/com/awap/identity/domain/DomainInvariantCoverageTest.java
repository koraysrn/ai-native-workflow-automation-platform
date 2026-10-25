package com.awap.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.domain.OutboxEvent;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.domain.WorkflowState;
import com.awap.form.domain.FormDefinition;
import com.awap.task.domain.Task;
import com.awap.task.domain.TaskStatus;
import com.awap.workflow.domain.WorkflowDefinition;
import com.awap.workflow.domain.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Exercises every getter, constructor branch and guard of the mutation-tested domain classes so PIT
 * cannot leave unmeasured code paths (issue #85 completeness gate).
 */
class DomainInvariantCoverageTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID USER = UUID.randomUUID();

  @Test
  void taskExposesEveryFieldAndGuardPath() {
    UUID instance = UUID.randomUUID();
    UUID node = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    Task task = new Task(TENANT, instance, node, "Review", "desc", null);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.OPEN);
    assertThat(task.getTitle()).isEqualTo("Review");
    assertThat(task.getDescription()).isEqualTo("desc");
    assertThat(task.getWorkflowInstanceId()).isEqualTo(instance);
    assertThat(task.getNodeExecutionId()).isEqualTo(node);
    assertThat(task.getTenantId()).isEqualTo(TENANT);
    assertThat(task.getId()).isNotNull();
    assertThat(task.getAssigneeId()).isNull();
    assertThat(task.isEscalated()).isFalse();
    assertThat(task.getEscalateAt()).isNull();
    assertThat(task.getCompletedAt()).isNull();
    assertThat(task.getDecisionComment()).isNull();
    assertThat(task.getVersion()).isZero();
    assertThat(task.getCreatedAt()).isNotNull();
    assertThat(task.getUpdatedAt()).isNotNull();
    assertThat(task.isDeleted()).isFalse();

    task.assign(assignee);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
    task.reassign(USER);
    assertThat(task.getAssigneeId()).isEqualTo(USER);

    task.claim(USER);
    task.approve("ok");
    assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);
    assertThat(task.getDecisionComment()).isEqualTo("ok");
    assertThat(task.getCompletedAt()).isNotNull();

    task.markEscalationDue();
    assertThat(task.isEscalated()).isTrue();

    Task cancellable = new Task(TENANT, null, null, "X", null, null);
    cancellable.cancel();
    assertThat(cancellable.getStatus()).isEqualTo(TaskStatus.CANCELLED);

    Task escalatable = new Task(TENANT, null, null, "Y", null, null);
    Instant deadline = Instant.now().plusSeconds(60);
    escalatable.escalate(USER, deadline);
    assertThat(escalatable.getEscalateAt()).isEqualTo(deadline);
    assertThat(escalatable.isEscalated()).isTrue();
  }

  @Test
  void nodeExecutionExposesEveryFieldAndTransition() {
    UUID instance = UUID.randomUUID();
    NodeExecution node = new NodeExecution(TENANT, instance, "n1", "TASK");
    assertThat(node.getId()).isNotNull();
    assertThat(node.getTenantId()).isEqualTo(TENANT);
    assertThat(node.getWorkflowInstanceId()).isEqualTo(instance);
    assertThat(node.getNodeId()).isEqualTo("n1");
    assertThat(node.getNodeType()).isEqualTo("TASK");
    assertThat(node.getStatus()).isEqualTo(NodeExecutionStatus.PENDING);
    assertThat(node.getAttempt()).isZero();
    assertThat(node.getOutputJson()).isNull();
    assertThat(node.getError()).isNull();
    assertThat(node.getCreatedAt()).isNotNull();
    assertThat(node.getCompletedAt()).isNull();
    assertThat(node.getResumeAt()).isNull();
    assertThat(node.getBranchTokenCount()).isZero();
    assertThat(node.getMergedBranchCount()).isZero();
    assertThat(node.getJoinNodeId()).isNull();

    node.start();
    assertThat(node.getStatus()).isEqualTo(NodeExecutionStatus.RUNNING);
    assertThat(node.getAttempt()).isEqualTo(1);

    node.waitForExternal();
    assertThat(node.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);

    node.complete("{\"ok\":true}");
    assertThat(node.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(node.getOutputJson()).isEqualTo("{\"ok\":true}");
    assertThat(node.getCompletedAt()).isNotNull();

    NodeExecution failed = new NodeExecution(TENANT, instance, "n2", "TASK");
    failed.fail("boom");
    assertThat(failed.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(failed.getError()).isEqualTo("boom");

    NodeExecution delayed = new NodeExecution(TENANT, instance, "n3", "DELAY");
    Instant resumeAt = Instant.now().plusSeconds(5);
    delayed.scheduleResume(resumeAt);
    assertThat(delayed.getResumeAt()).isEqualTo(resumeAt);
    delayed.clearResumeAt();
    assertThat(delayed.getResumeAt()).isNull();

    NodeExecution forked = new NodeExecution(TENANT, instance, "n4", "PARALLEL");
    forked.openBranches(3);
    assertThat(forked.getBranchTokenCount()).isEqualTo(3);
    assertThat(forked.getMergedBranchCount()).isZero();
    forked.recordBranchMerge();
    assertThat(forked.getMergedBranchCount()).isEqualTo(1);
    forked.setJoinNodeId("join");
    assertThat(forked.getJoinNodeId()).isEqualTo("join");
  }

  @Test
  void workflowInstanceGuardsAndExposesState() {
    UUID definition = UUID.randomUUID();
    WorkflowInstance instance =
        new WorkflowInstance(TENANT, definition, 1, WorkflowState.RUNNING, "idem", "{}");
    assertThat(instance.getId()).isNotNull();
    assertThat(instance.getTenantId()).isEqualTo(TENANT);
    assertThat(instance.getWorkflowDefinitionId()).isEqualTo(definition);
    assertThat(instance.getWorkflowVersion()).isEqualTo(1);
    assertThat(instance.getState()).isEqualTo(WorkflowState.RUNNING);
    assertThat(instance.getContextJson()).isEqualTo("{}");
    assertThat(instance.getIdempotencyKey()).isEqualTo("idem");
    assertThat(instance.getVersion()).isZero();
    assertThat(instance.getCreatedAt()).isNotNull();
    assertThat(instance.getUpdatedAt()).isNotNull();
    assertThat(instance.isDeleted()).isFalse();

    instance.transitionTo(WorkflowState.WAITING);
    assertThat(instance.getState()).isEqualTo(WorkflowState.WAITING);

    instance.pause();
    assertThat(instance.getState()).isEqualTo(WorkflowState.PAUSED);
    instance.resume();
    assertThat(instance.getState()).isEqualTo(WorkflowState.RUNNING);

    // resume() is only valid from PAUSED.
    assertThatThrownBy(() -> instance.resume()).isInstanceOf(IllegalStateException.class);

    WorkflowInstance completed =
        new WorkflowInstance(TENANT, definition, 1, WorkflowState.COMPLETED, null, "{}");
    assertThatThrownBy(() -> completed.transitionTo(WorkflowState.RUNNING))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> completed.pause()).isInstanceOf(IllegalStateException.class);

    WorkflowInstance failed =
        new WorkflowInstance(TENANT, definition, 1, WorkflowState.FAILED, null, "{}");
    assertThatThrownBy(() -> failed.transitionTo(WorkflowState.WAITING))
        .isInstanceOf(IllegalStateException.class);
    failed.transitionTo(WorkflowState.RUNNING);
    assertThat(failed.getState()).isEqualTo(WorkflowState.RUNNING);
  }

  @Test
  void workflowDefinitionAndFormDefinitionExposeVersions() {
    WorkflowDefinition definition = new WorkflowDefinition(TENANT, "Flow", "desc");
    assertThat(definition.getId()).isNotNull();
    assertThat(definition.getTenantId()).isEqualTo(TENANT);
    assertThat(definition.getName()).isEqualTo("Flow");
    assertThat(definition.getDescription()).isEqualTo("desc");
    assertThat(definition.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
    assertThat(definition.getCurrentVersion()).isZero();
    assertThat(definition.getCreatedAt()).isNotNull();
    assertThat(definition.getUpdatedAt()).isNotNull();
    assertThat(definition.isDeleted()).isFalse();
    definition.publish(2);
    assertThat(definition.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);
    assertThat(definition.getCurrentVersion()).isEqualTo(2);
    definition.rollbackTo(3);
    assertThat(definition.getCurrentVersion()).isEqualTo(3);

    FormDefinition form = new FormDefinition(TENANT, "Form", "{\"fields\":[]}");
    assertThat(form.getId()).isNotNull();
    assertThat(form.getTenantId()).isEqualTo(TENANT);
    assertThat(form.getName()).isEqualTo("Form");
    assertThat(form.getSchemaJson()).isEqualTo("{\"fields\":[]}");
    assertThat(form.getVersion()).isEqualTo(1);
    assertThat(form.getCreatedAt()).isNotNull();
    assertThat(form.getUpdatedAt()).isNotNull();
    assertThat(form.isDeleted()).isFalse();
    form.updateSchema("Form v2", "{\"fields\":[{\"id\":\"a\",\"type\":\"TEXT\"}]}");
    assertThat(form.getVersion()).isEqualTo(2);
    assertThat(form.getName()).isEqualTo("Form v2");
  }

  @Test
  void tenantAndUserExposeEveryField() {
    Tenant tenant = new Tenant("acme", "Acme", "FREE", 10, 5, 1000);
    assertThat(tenant.getId()).isNotNull();
    assertThat(tenant.getSlug()).isEqualTo("acme");
    assertThat(tenant.getName()).isEqualTo("Acme");
    assertThat(tenant.getPlan()).isEqualTo("FREE");
    assertThat(tenant.getMaxUsers()).isEqualTo(10);
    assertThat(tenant.getMaxWorkflows()).isEqualTo(5);
    assertThat(tenant.getMaxAiCallsPerMonth()).isEqualTo(1000L);
    assertThat(tenant.isActive()).isTrue();
    assertThat(tenant.getCreatedAt()).isNotNull();
    assertThat(tenant.getUpdatedAt()).isNotNull();
    assertThat(tenant.isDeleted()).isFalse();
    tenant.updateLimits("PRO", 20, 10, 5000);
    assertThat(tenant.getPlan()).isEqualTo("PRO");
    assertThat(tenant.getMaxUsers()).isEqualTo(20);

    User user = new User(TENANT, "ada@acme.com", "hash", "Ada", Role.TENANT_ADMIN);
    assertThat(user.getId()).isNotNull();
    assertThat(user.getTenantId()).isEqualTo(TENANT);
    assertThat(user.getEmail()).isEqualTo("ada@acme.com");
    assertThat(user.getPasswordHash()).isEqualTo("hash");
    assertThat(user.getFullName()).isEqualTo("Ada");
    assertThat(user.getRole()).isEqualTo(Role.TENANT_ADMIN);
    assertThat(user.isDeleted()).isFalse();
    assertThat(user.isLocked()).isFalse();

    for (int i = 0; i < 5; i++) {
      user.registerFailedLogin();
    }
    assertThat(user.isLocked()).isTrue();
    user.resetFailedLogin();
    assertThat(user.isLocked()).isFalse();
    user.assignRole(Role.MANAGER);
    assertThat(user.getRole()).isEqualTo(Role.MANAGER);
  }

  @Test
  void outboxEventExposesEveryField() {
    UUID aggregate = UUID.randomUUID();
    OutboxEvent event = new OutboxEvent(TENANT, "TASK", aggregate, "TASK_COMPLETED", "{}");
    assertThat(event.getId()).isNotNull();
    assertThat(event.getTenantId()).isEqualTo(TENANT);
    assertThat(event.getAggregateType()).isEqualTo("TASK");
    assertThat(event.getAggregateId()).isEqualTo(aggregate);
    assertThat(event.getEventType()).isEqualTo("TASK_COMPLETED");
    assertThat(event.getPayloadJson()).isEqualTo("{}");
    assertThat(event.getCreatedAt()).isNotNull();
    assertThat(event.isPublished()).isFalse();
    event.markPublished();
    assertThat(event.isPublished()).isTrue();
  }
}
