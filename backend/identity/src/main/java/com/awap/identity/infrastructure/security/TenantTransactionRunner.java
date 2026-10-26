package com.awap.identity.infrastructure.security;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens a transaction for non-HTTP work (Kafka consumers, schedulers) after the caller has
 * established {@link TenantContext}. The transaction aspect then applies the PostgreSQL {@code
 * awap.tenant_id} setting to the connection, which is what makes RLS work off the request thread.
 */
@Service
public class TenantTransactionRunner {

  @Transactional
  public void run(Runnable action) {
    action.run();
  }
}
