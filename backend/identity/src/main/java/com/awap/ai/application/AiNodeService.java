package com.awap.ai.application;

import com.awap.ai.domain.AiConfidenceEstimator;
import com.awap.ai.domain.AiGuardrailViolationException;
import com.awap.ai.domain.AiGuardrails;
import com.awap.ai.domain.AutomationAudit;
import com.awap.ai.infrastructure.AutomationAuditRepository;
import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AI workflow node: guardrails → budget → cache → provider call → confidence/HITL → immutable
 * audit. The node is fail-closed: with {@code awap.ai.node-enabled=false} it refuses to run instead
 * of silently degrading.
 */
@Service
public class AiNodeService {

  private static final Logger log = LoggerFactory.getLogger(AiNodeService.class);
  private static final int CACHE_SIZE = 256;

  private final AiAgentService agentService;
  private final AutomationAuditRepository auditRepository;
  private final AiBudgetGuard budgetGuard;
  private final MeterRegistry meterRegistry;
  private final boolean enabled;
  private final double confidenceThreshold;
  private final long costMicrosPerToken;
  private final Map<String, AiNodeResult> cache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AiNodeResult> eldest) {
          return size() > CACHE_SIZE;
        }
      };

  @Autowired
  public AiNodeService(
      AiAgentService agentService,
      AutomationAuditRepository auditRepository,
      AiBudgetGuard budgetGuard,
      MeterRegistry meterRegistry,
      @Value("${awap.ai.node-enabled:false}") boolean enabled,
      @Value("${awap.ai.confidence-threshold:0.7}") double confidenceThreshold,
      @Value("${awap.ai.cost-micros-per-token:2}") long costMicrosPerToken) {
    this.agentService = agentService;
    this.auditRepository = auditRepository;
    this.budgetGuard = budgetGuard;
    this.meterRegistry = meterRegistry;
    this.enabled = enabled;
    this.confidenceThreshold = confidenceThreshold;
    this.costMicrosPerToken = Math.max(0, costMicrosPerToken);
  }

  /** Runs the AI action for a workflow node and returns the validated result. */
  public AiNodeResult evaluate(
      UUID tenantId, UUID instanceId, String nodeId, AiAction action, String input) {
    if (!enabled) {
      audit(
          tenantId,
          instanceId,
          nodeId,
          action,
          "",
          null,
          0,
          0,
          "FAILED",
          "AI node disabled by configuration");
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.NOT_IMPLEMENTED,
          "AI node execution is not enabled in this deployment.");
    }

    String prompt;
    try {
      prompt = AiGuardrails.sanitize(action.name().toLowerCase(Locale.ROOT) + ": " + input);
    } catch (AiGuardrailViolationException ex) {
      meterRegistry.counter("awap.ai.requests", "outcome", "blocked").increment();
      audit(
          tenantId,
          instanceId,
          nodeId,
          action,
          hash(input),
          null,
          0,
          0,
          "BLOCKED",
          ex.getMessage());
      throw new AwapException(
          ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "AI prompt rejected by guardrails.");
    }

    String promptHash = hash(prompt);
    synchronized (cache) {
      AiNodeResult cached = cache.get(promptHash);
      if (cached != null) {
        meterRegistry.counter("awap.ai.requests", "outcome", "cache_hit").increment();
        audit(
            tenantId,
            instanceId,
            nodeId,
            action,
            promptHash,
            cached.output(),
            cached.tokens(),
            0,
            "CACHE_HIT",
            "served from cache");
        return cached;
      }
    }

    int estimatedTokens = Math.max(1, prompt.length() / 4);
    if (!budgetGuard.reserve(tenantId, estimatedTokens)) {
      meterRegistry.counter("awap.ai.requests", "outcome", "budget_exceeded").increment();
      audit(
          tenantId,
          instanceId,
          nodeId,
          action,
          promptHash,
          null,
          estimatedTokens,
          0,
          "BUDGET_EXCEEDED",
          "tenant monthly token budget exhausted");
      throw new AwapException(
          ErrorCode.QUOTA_EXCEEDED,
          HttpStatus.TOO_MANY_REQUESTS,
          "AI token budget exhausted for this tenant.");
    }

    String output =
        action == AiAction.SUMMARIZE ? agentService.summarize(input) : agentService.classify(input);
    double confidence = AiConfidenceEstimator.estimate(output);
    boolean requiresHumanApproval = confidence < confidenceThreshold;
    long costMicros = estimatedTokens * costMicrosPerToken;
    AiNodeResult result = new AiNodeResult(output, confidence, requiresHumanApproval, false);

    synchronized (cache) {
      cache.put(promptHash, result);
    }
    meterRegistry
        .counter("awap.ai.requests", "outcome", requiresHumanApproval ? "hitl" : "success")
        .increment();
    meterRegistry.counter("awap.ai.tokens").increment(estimatedTokens);
    meterRegistry.counter("awap.ai.cost.micros").increment(costMicros);
    if (requiresHumanApproval) {
      log.info(
          "AI node {} on instance {} downgraded to HITL (confidence {} < {})",
          nodeId,
          instanceId,
          confidence,
          confidenceThreshold);
    }
    audit(
        tenantId,
        instanceId,
        nodeId,
        action,
        promptHash,
        output,
        estimatedTokens,
        costMicros,
        requiresHumanApproval ? "HITL" : "SUCCESS",
        requiresHumanApproval ? "confidence below threshold" : null);
    return result;
  }

  private void audit(
      UUID tenantId,
      UUID instanceId,
      String nodeId,
      AiAction action,
      String promptHash,
      String output,
      int tokens,
      long costMicros,
      String outcome,
      String detail) {
    auditRepository.save(
        new AutomationAudit(
            tenantId,
            instanceId,
            nodeId == null ? "ai" : nodeId,
            AutomationAudit.AuditKind.AI,
            action.name(),
            "local-heuristic",
            "awap-local",
            promptHash,
            output == null ? null : BigDecimal.valueOf(AiConfidenceEstimator.estimate(output)),
            tokens,
            costMicros,
            outcome,
            detail));
  }

  private static String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  /** Supported AI actions for a workflow node. */
  public enum AiAction {
    SUMMARIZE,
    CLASSIFY
  }

  /** Result of an AI node evaluation. */
  public record AiNodeResult(
      String output, double confidence, boolean requiresHumanApproval, boolean fromCache) {

    public int tokens() {
      return Math.max(1, output == null ? 0 : output.length() / 4);
    }
  }
}
