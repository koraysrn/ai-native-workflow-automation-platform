package com.awap.identity.infrastructure.security;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Establishes the tenant context for Kafka consumers and runs the handler inside a transaction
 * started <b>after</b> the context exists. HTTP requests get the context from the JWT filter;
 * consumer threads have no request, so without this the RLS predicates would evaluate to NULL and
 * tenant-scoped writes (notifications, analytics, documents) would be rejected.
 */
@Component
public class KafkaTenantContext {

  private final TenantTransactionRunner transactionRunner;

  public KafkaTenantContext(TenantTransactionRunner transactionRunner) {
    this.transactionRunner = transactionRunner;
  }

  public void run(UUID tenantId, Runnable action) {
    TenantContext.set(tenantId, null, "SYSTEM");
    try {
      transactionRunner.run(action);
    } finally {
      TenantContext.clear();
    }
  }
}
