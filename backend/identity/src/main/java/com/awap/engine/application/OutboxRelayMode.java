package com.awap.engine.application;

/**
 * The two ways the outbox relay can read and release outbox rows (issue #122).
 *
 * <p>The mode is selected by {@code awap.outbox.datasource.enabled} and reported everywhere the
 * relay is observed: the {@code awap.outbox.relay.mode} metric, the {@code outboxRelay} actuator
 * health indicator and the startup log. Operators must be able to tell from the outside which path
 * is live, because the two paths do not have the same guarantees.
 *
 * <ul>
 *   <li>{@link #JDBC} — the dedicated outbox pool calls the {@code SECURITY DEFINER} claim/release
 *       functions (V37), so the relay sees every tenant even though {@code awap_app} is bound by
 *       {@code FORCE ROW LEVEL SECURITY}. This is the production path and the default.
 *   <li>{@link #JPA} — the fallback shares the request-serving pool and goes through the JPA
 *       repository. It runs <em>without</em> a tenant context, so PostgreSQL RLS hides every outbox
 *       row. It exists only for single-connection development/test environments and is rejected at
 *       startup in {@code prod}/{@code staging}.
 * </ul>
 */
public enum OutboxRelayMode {
  JDBC("jdbc", true),
  JPA("jpa", false);

  private final String tag;
  private final boolean rlsSafe;

  OutboxRelayMode(String tag, boolean rlsSafe) {
    this.tag = tag;
    this.rlsSafe = rlsSafe;
  }

  /** Low-cardinality value used for the {@code mode} metric tag and the health details. */
  public String tag() {
    return tag;
  }

  /**
   * Whether this path can see tenants outside the current tenant context. Only the {@code SECURITY
   * DEFINER} path can, which is why the fallback is restricted to dev/test.
   */
  public boolean rlsSafe() {
    return rlsSafe;
  }
}
