package com.awap.script.application;

import com.awap.ai.domain.AutomationAudit;
import com.awap.ai.infrastructure.AutomationAuditRepository;
import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.script.domain.ScriptSandboxException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * SCRIPT workflow node. Fail-closed behind {@code awap.script.enabled}; the script sees only the
 * typed {@code ctx} map (no environment, secrets, filesystem, network or process access), runs
 * under the sandbox timeout and every attempt is written to the immutable automation audit.
 */
@Service
public class ScriptNodeService {

  private final ScriptSandbox sandbox;
  private final AutomationAuditRepository auditRepository;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final int maxContextChars;

  @Autowired
  public ScriptNodeService(
      ScriptSandbox sandbox,
      AutomationAuditRepository auditRepository,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper,
      @Value("${awap.script.enabled:false}") boolean enabled,
      @Value("${awap.script.max-context-chars:32768}") int maxContextChars) {
    this.sandbox = sandbox;
    this.auditRepository = auditRepository;
    this.meterRegistry = meterRegistry;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.maxContextChars = Math.max(1, maxContextChars);
  }

  /** Executes the script against the JSON context and returns the rendered output. */
  public ScriptResult execute(
      UUID tenantId, UUID instanceId, String nodeId, String script, String contextJson) {
    if (!enabled) {
      audit(tenantId, instanceId, nodeId, script, "FAILED", "SCRIPT node disabled");
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.NOT_IMPLEMENTED,
          "SCRIPT node execution is not enabled in this deployment.");
    }
    if (contextJson != null && contextJson.length() > maxContextChars) {
      audit(tenantId, instanceId, nodeId, script, "FAILED", "context too large");
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "Script context is too large.");
    }
    Map<String, Object> context = parseContext(contextJson);
    try {
      ScriptSandbox.Result result = sandbox.evaluate(script, context);
      meterRegistry.counter("awap.script.executions", "outcome", "success").increment();
      audit(tenantId, instanceId, nodeId, script, "SUCCESS", result.rendered());
      return new ScriptResult(
          result.rendered(), result.value() == null ? "null" : typeName(result.value()));
    } catch (ScriptSandboxException ex) {
      String outcome = ex.isTimeout() ? "TIMEOUT" : "FAILED";
      meterRegistry
          .counter("awap.script.executions", "outcome", ex.isTimeout() ? "timeout" : "failed")
          .increment();
      audit(tenantId, instanceId, nodeId, script, outcome, ex.getMessage());
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          ex.isTimeout() ? HttpStatus.REQUEST_TIMEOUT : HttpStatus.BAD_REQUEST,
          "SCRIPT node failed: " + ex.getMessage());
    }
  }

  private Map<String, Object> parseContext(String contextJson) {
    if (contextJson == null || contextJson.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(contextJson, new TypeReference<Map<String, Object>>() {});
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.BAD_REQUEST,
          "Script context must be a JSON object.");
    }
  }

  private void audit(
      UUID tenantId, UUID instanceId, String nodeId, String script, String outcome, String detail) {
    auditRepository.save(
        new AutomationAudit(
            tenantId,
            instanceId,
            nodeId == null ? "script" : nodeId,
            AutomationAudit.AuditKind.SCRIPT,
            "EVALUATE",
            "awap-sandbox",
            "expression-v1",
            hash(script),
            null,
            0,
            0,
            outcome,
            detail));
  }

  private static String typeName(Object value) {
    if (value instanceof Number) {
      return "number";
    }
    if (value instanceof Boolean) {
      return "boolean";
    }
    return "string";
  }

  private static String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String safe = value == null ? "" : value;
      return HexFormat.of().formatHex(digest.digest(safe.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  /** Sandboxed script result. */
  public record ScriptResult(String output, String type) {}
}
