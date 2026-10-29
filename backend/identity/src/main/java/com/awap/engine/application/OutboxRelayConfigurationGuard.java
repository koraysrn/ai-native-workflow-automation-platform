package com.awap.engine.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resolves and validates the active outbox relay path (issue #122).
 *
 * <p>{@code OUTBOX_SEPARATE_POOL=false} makes the relay fall back to the JPA repository. That path
 * runs with the request-serving pool and <strong>without a tenant context</strong>, while {@code
 * outbox_events} is guarded by {@code FORCE ROW LEVEL SECURITY}: {@code current_setting('awap.
 * tenant_id', true)} is null, so every row is filtered out and event delivery stops silently.
 *
 * <p>Because a misconfiguration of this single property can silently stop the whole event pipeline,
 * the class fails application startup when the fallback is selected in {@code prod} or {@code
 * staging}, and rejects a malformed property value or relay datasource name in every environment.
 * The fallback stays available in dev/test environments, where a single-connection database without
 * RLS policies is the normal case; a warning documents the restriction there.
 */
@Component
public class OutboxRelayConfigurationGuard {

  public static final String ENABLED_PROPERTY = "awap.outbox.datasource.enabled";
  public static final String DATASOURCE_NAME_PROPERTY = "awap.outbox.datasource.name";
  public static final String DEFAULT_DATASOURCE_NAME = "awap-outbox-relay";

  private static final Logger log = LoggerFactory.getLogger(OutboxRelayConfigurationGuard.class);
  private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "staging");
  private static final Pattern VALID_DATASOURCE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");

  private final OutboxRelayMode mode;
  private final String datasourceName;
  private final Set<String> productionProfiles;

  public OutboxRelayConfigurationGuard(
      Environment environment,
      @Value("${awap.outbox.datasource.enabled:true}") String enabledValue,
      @Value("${awap.outbox.datasource.name:" + DEFAULT_DATASOURCE_NAME + "}")
          String datasourceName) {
    this.mode = parseMode(enabledValue);
    this.datasourceName = validateDatasourceName(datasourceName, mode);
    this.productionProfiles = activeProductionProfiles(environment);
    rejectFallbackInProduction(mode, productionProfiles);
    if (mode == OutboxRelayMode.JPA) {
      log.warn(
          "{}={} selects the JPA outbox fallback: the relay runs without a tenant context, so"
              + " PostgreSQL row-level security hides every outbox row. This mode is only supported"
              + " in single-connection dev/test environments; see runbook O8.",
          ENABLED_PROPERTY,
          enabledValue);
    } else {
      log.info(
          "Outbox relay mode is '{}' (dedicated pool '{}', SECURITY DEFINER claim/release).",
          mode.tag(),
          datasourceName);
    }
  }

  /** The path this process uses; reported by the metric and the health indicator. */
  public OutboxRelayMode mode() {
    return mode;
  }

  /** Name of the dedicated outbox connection pool (also the Hikari pool name). */
  public String datasourceName() {
    return datasourceName;
  }

  /** Whether the dev/test-only JPA fallback is active. */
  public boolean jpaFallback() {
    return mode == OutboxRelayMode.JPA;
  }

  /** Production-like profiles that are active, empty outside prod/staging. */
  public Set<String> productionProfiles() {
    return productionProfiles;
  }

  private static OutboxRelayMode parseMode(String rawValue) {
    String value = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
    if (!"true".equals(value) && !"false".equals(value)) {
      throw new IllegalStateException(
          ENABLED_PROPERTY
              + " must be exactly 'true' or 'false' but was '"
              + rawValue
              + "'. true (the default via OUTBOX_SEPARATE_POOL) enables the dedicated outbox pool;"
              + " false selects the dev/test-only JPA fallback.");
    }
    return "true".equals(value) ? OutboxRelayMode.JDBC : OutboxRelayMode.JPA;
  }

  private static String validateDatasourceName(String name, OutboxRelayMode mode) {
    String candidate = name == null ? "" : name.trim();
    if (mode == OutboxRelayMode.JPA) {
      // The fallback does not create a pool, so the name is unused; keep the default.
      return candidate.isEmpty() ? DEFAULT_DATASOURCE_NAME : candidate;
    }
    if (!VALID_DATASOURCE_NAME.matcher(candidate).matches()) {
      throw new IllegalStateException(
          DATASOURCE_NAME_PROPERTY
              + " must match [a-z][a-z0-9-]{0,62} but was '"
              + name
              + "'. It is used as the dedicated outbox connection-pool name; a misconfigured pool"
              + " name hides the relay in connection-pool metrics and logs.");
    }
    return candidate;
  }

  private static Set<String> activeProductionProfiles(Environment environment) {
    Set<String> active = new LinkedHashSet<>();
    for (String profile : environment.getActiveProfiles()) {
      String normalized = profile.trim().toLowerCase(Locale.ROOT);
      if (PRODUCTION_PROFILES.contains(normalized)) {
        active.add(normalized);
      }
    }
    return Set.copyOf(active);
  }

  private static void rejectFallbackInProduction(
      OutboxRelayMode mode, Set<String> productionProfiles) {
    if (mode != OutboxRelayMode.JPA || productionProfiles.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "The JPA outbox fallback is not allowed with the active profile(s) "
            + List.copyOf(productionProfiles)
            + ": "
            + ENABLED_PROPERTY
            + "=false makes the relay read outbox_events through JPA without a tenant context, so"
            + " PostgreSQL row-level security hides every row and events stop being published"
            + " without any error. Remove OUTBOX_SEPARATE_POOL=false (the dedicated pool is the"
            + " default) or run this configuration only in a dev/test environment.");
  }
}
