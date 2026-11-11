package com.awap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.ai.domain.AutomationAudit;
import com.awap.ai.domain.AutomationAudit.AuditKind;
import com.awap.ai.infrastructure.AutomationAuditRepository;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.NodeExecutionStatus;
import com.awap.engine.infrastructure.NodeExecutionRepository;
import com.awap.identity.IdentityApplication;
import com.awap.organization.domain.Company;
import com.awap.organization.infrastructure.CompanyRepository;
import com.awap.task.domain.Task;
import com.awap.task.domain.TaskStatus;
import com.awap.task.infrastructure.TaskRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #199: proves that the documented JPA-entity exception preserves behaviour and every mapped
 * field across a persistence round-trip, and that a rollback leaves no partial aggregate behind.
 */
@SpringBootTest(classes = IdentityApplication.class)
class DomainPersistenceRoundTripTest {

  @Autowired private TaskRepository taskRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private AutomationAuditRepository automationAuditRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void taskRoundTripPreservesAllMappedFields() {
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID nodeExecution = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    Task task = new Task(tenant, instance, nodeExecution, "Review invoice", "Q4 review", null);
    task.claim(assignee);
    Task saved = taskRepository.saveAndFlush(task);

    Task reloaded = taskRepository.findById(saved.getId()).orElseThrow();

    assertThat(reloaded.getTenantId()).isEqualTo(tenant);
    assertThat(reloaded.getWorkflowInstanceId()).isEqualTo(instance);
    assertThat(reloaded.getNodeExecutionId()).isEqualTo(nodeExecution);
    assertThat(reloaded.getTitle()).isEqualTo("Review invoice");
    assertThat(reloaded.getDescription()).isEqualTo("Q4 review");
    assertThat(reloaded.getAssigneeId()).isEqualTo(assignee);
    assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(reloaded.isEscalated()).isFalse();
    assertThat(reloaded.isDeleted()).isFalse();
    assertThat(reloaded.getCreatedAt()).isNotNull();
    assertThat(reloaded.getUpdatedAt()).isNotNull();
  }

  @Test
  void companyRoundTripPreservesTheSoftDeleteFlag() {
    UUID tenant = UUID.randomUUID();
    Company saved = companyRepository.saveAndFlush(new Company(tenant, "Acme"));

    Company reloaded = companyRepository.findById(saved.getId()).orElseThrow();

    assertThat(reloaded.getTenantId()).isEqualTo(tenant);
    assertThat(reloaded.getName()).isEqualTo("Acme");
    assertThat(reloaded.isDeleted()).isFalse();
    assertThat(reloaded.getCreatedAt()).isNotNull();
    assertThat(reloaded.getUpdatedAt()).isNotNull();
  }

  @Test
  void nodeExecutionRoundTripPreservesTheStateMachineFields() {
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    NodeExecution execution = new NodeExecution(tenant, instance, "approve", "APPROVAL");
    execution.start();
    NodeExecution saved = nodeExecutionRepository.saveAndFlush(execution);

    NodeExecution reloaded =
        nodeExecutionRepository
            .findByWorkflowInstanceIdAndNodeId(instance, "approve")
            .orElseThrow();

    assertThat(reloaded.getId()).isEqualTo(saved.getId());
    assertThat(reloaded.getTenantId()).isEqualTo(tenant);
    assertThat(reloaded.getNodeType()).isEqualTo("APPROVAL");
    assertThat(reloaded.getStatus()).isEqualTo(NodeExecutionStatus.RUNNING);
    assertThat(reloaded.getAttempt()).isEqualTo(1);
    assertThat(reloaded.getCreatedAt()).isNotNull();
  }

  @Test
  void automationAuditRoundTripPreservesEveryValue() {
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    automationAuditRepository.saveAndFlush(
        new AutomationAudit(
            tenant,
            instance,
            "ai-node",
            AuditKind.AI,
            "chat.completion",
            "openai",
            "gpt-4.1",
            "hash-123",
            new BigDecimal("0.87"),
            120,
            350L,
            "SUCCESS",
            "{\"tokens\":120}"));

    List<AutomationAudit> reloaded =
        automationAuditRepository.findByTenantIdAndInstanceIdOrderByCreatedAtAsc(tenant, instance);

    assertThat(reloaded).hasSize(1);
    AutomationAudit audit = reloaded.getFirst();
    assertThat(audit.getNodeId()).isEqualTo("ai-node");
    assertThat(audit.getKind()).isEqualTo(AuditKind.AI);
    assertThat(audit.getAction()).isEqualTo("chat.completion");
    assertThat(audit.getProvider()).isEqualTo("openai");
    assertThat(audit.getModel()).isEqualTo("gpt-4.1");
    assertThat(audit.getPromptHash()).isEqualTo("hash-123");
    assertThat(audit.getConfidence()).isEqualByComparingTo("0.87");
    assertThat(audit.getTokens()).isEqualTo(120);
    assertThat(audit.getCostMicros()).isEqualTo(350L);
    assertThat(audit.getOutcome()).isEqualTo("SUCCESS");
    assertThat(audit.getDetail()).isEqualTo("{\"tokens\":120}");
    assertThat(audit.getCreatedAt()).isNotNull();
  }

  @Test
  void aRolledBackTransactionLeavesNoAggregateBehind() {
    UUID tenant = UUID.randomUUID();
    TransactionTemplate template = new TransactionTemplate(transactionManager);

    template.execute(
        status -> {
          taskRepository.saveAndFlush(new Task(tenant, null, null, "Rollback", null, null));
          status.setRollbackOnly();
          return null;
        });

    assertThat(taskRepository.findByTenantIdAndDeletedFalse(tenant)).isEmpty();
  }
}
