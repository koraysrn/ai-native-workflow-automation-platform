package com.awap.identity.infrastructure.security;

import java.util.Optional;
import java.util.UUID;

/** Holds the tenant and user context of the current request, propagated across all layers. */
public final class TenantContext {

  private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

  private TenantContext() {}

  public static void set(UUID tenantId, UUID userId, String role) {
    HOLDER.set(new Context(tenantId, userId, role));
  }

  public static Optional<Context> current() {
    return Optional.ofNullable(HOLDER.get());
  }

  public static UUID requireTenantId() {
    Context context = HOLDER.get();
    if (context == null) {
      throw new IllegalStateException("Tenant context is not available");
    }
    return context.tenantId();
  }

  public static void clear() {
    HOLDER.remove();
  }

  /** Immutable snapshot of the authenticated caller. */
  public record Context(UUID tenantId, UUID userId, String role) {}
}
