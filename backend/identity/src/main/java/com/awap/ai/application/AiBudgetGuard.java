package com.awap.ai.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-tenant token budget for AI calls. The counter is in-memory (per instance) and intentionally
 * conservative: it reserves the estimated tokens before the call and refuses once the budget is
 * exhausted, so a runaway workflow cannot spend without bound.
 */
@Component
public class AiBudgetGuard {

  private final long monthlyTokenBudget;
  private final Map<UUID, AtomicLong> usedTokens = new ConcurrentHashMap<>();

  @Autowired
  public AiBudgetGuard(@Value("${awap.ai.monthly-token-budget:200000}") long monthlyTokenBudget) {
    this.monthlyTokenBudget = Math.max(1, monthlyTokenBudget);
  }

  /** Reserves tokens or returns {@code false} when the tenant budget would be exceeded. */
  public boolean reserve(UUID tenantId, int tokens) {
    AtomicLong used = usedTokens.computeIfAbsent(tenantId, ignored -> new AtomicLong());
    synchronized (used) {
      long next = used.get() + Math.max(0, tokens);
      if (next > monthlyTokenBudget) {
        return false;
      }
      used.set(next);
      return true;
    }
  }

  /** Tokens consumed by the tenant so far (for tests and diagnostics). */
  public long used(UUID tenantId) {
    AtomicLong used = usedTokens.get(tenantId);
    return used == null ? 0 : used.get();
  }

  public long budget() {
    return monthlyTokenBudget;
  }

  /** Test/support hook to reset a tenant budget window. */
  public void reset(UUID tenantId) {
    usedTokens.remove(tenantId);
  }
}
