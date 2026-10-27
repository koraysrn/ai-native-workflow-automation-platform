package com.awap.script.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.awap.ai.domain.AutomationAudit;
import com.awap.ai.infrastructure.AutomationAuditRepository;
import com.awap.common.api.AwapException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScriptNodeServiceTest {

  private final AutomationAuditRepository auditRepository = mock(AutomationAuditRepository.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final UUID tenantId = UUID.randomUUID();
  private final UUID instanceId = UUID.randomUUID();

  private ScriptNodeService service(boolean enabled, ScriptSandbox sandbox) {
    return new ScriptNodeService(
        sandbox, auditRepository, registry, new ObjectMapper(), enabled, 256);
  }

  private ScriptSandbox sandbox() {
    return new ScriptSandbox(1_000, 256, Executors.newVirtualThreadPerTaskExecutor(), 0);
  }

  @Test
  void disabledNodeFailsClosed() {
    ScriptNodeService service = service(false, sandbox());

    assertThatThrownBy(() -> service.execute(tenantId, instanceId, "s1", "1 + 1", "{}"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(501));
    assertThat(lastAudit().getOutcome()).isEqualTo("FAILED");
  }

  @Test
  void computesAValueAndAuditsTheExecution() {
    ScriptNodeService service = service(true, sandbox());

    ScriptNodeService.ScriptResult result =
        service.execute(tenantId, instanceId, "s1", "ctx.total * 2", "{\"total\": 21}");

    assertThat(result.output()).isEqualTo("42");
    assertThat(result.type()).isEqualTo("number");
    AutomationAudit audit = lastAudit();
    assertThat(audit.getKind()).isEqualTo(AutomationAudit.AuditKind.SCRIPT);
    assertThat(audit.getOutcome()).isEqualTo("SUCCESS");
    assertThat(audit.getPromptHash()).hasSize(64);
    assertThat(registry.get("awap.script.executions").tag("outcome", "success").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void timeoutReturns408AndIsAudited() {
    ScriptSandbox slow =
        new ScriptSandbox(5, 256, Executors.newVirtualThreadPerTaskExecutor(), 200);
    ScriptNodeService service = service(true, slow);

    assertThatThrownBy(() -> service.execute(tenantId, instanceId, "s1", "1 + 1", "{}"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(408));
    assertThat(lastAudit().getOutcome()).isEqualTo("TIMEOUT");
  }

  @Test
  void rejectsNonObjectContextAndOversizedPayloads() {
    ScriptNodeService service = service(true, sandbox());

    assertThatThrownBy(() -> service.execute(tenantId, instanceId, "s1", "1 + 1", "[1,2]"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
    assertThatThrownBy(
            () ->
                service.execute(
                    tenantId, instanceId, "s1", "1 + 1", "{\"blob\":\"" + "a".repeat(300) + "\"}"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
  }

  @Test
  void blockingScriptIsAuditedAsFailure() {
    ScriptNodeService service = service(true, sandbox());

    assertThatThrownBy(
            () -> service.execute(tenantId, instanceId, "s1", "Runtime.getRuntime()", "{}"))
        .isInstanceOf(AwapException.class)
        .satisfies(ex -> assertThat(((AwapException) ex).status().value()).isEqualTo(400));
    assertThat(lastAudit().getOutcome()).isEqualTo("FAILED");
  }

  private AutomationAudit lastAudit() {
    ArgumentCaptor<AutomationAudit> captor = ArgumentCaptor.forClass(AutomationAudit.class);
    verify(auditRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getAllValues().get(captor.getAllValues().size() - 1);
  }
}
