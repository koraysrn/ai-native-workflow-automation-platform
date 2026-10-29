package com.awap.usage.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.usage.application.UsageCounterStore.IncrementResult;
import com.awap.usage.application.UsageCounterStore.Outcome;
import com.awap.usage.domain.UsageEvent;
import com.awap.usage.domain.UsageMetric;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meters tenant resource consumption and enforces daily quotas.
 *
 * <p><b>Failure policy</b> (issue #125 risk 8):
 *
 * <ul>
 *   <li>{@link #record} — observational metering (task decisions, document bytes) is
 *       <b>fail-open</b>: a metering outage logs a warning and increments {@code
 *       awap.usage.metering.failures}, it never blocks the business operation.
 *   <li>{@link #reserve} — quota-enforcing metering (workflow starts, AI calls) is
 *       <b>fail-closed</b> by default: if the counter cannot be read or written the call is
 *       rejected with {@code 503 AWAP-IDN-0008}. Setting {@code awap.usage.fail-open=true} turns
 *       this into best-effort (availability over strict quota).
 * </ul>
 *
 * <p>Duplicate delivery is not an error: the store returns {@link Outcome#DUPLICATE} and the
 * service treats it as already counted.
 */
@Service
public class UsageMeteringService {

  static final double NEAR_LIMIT_RATIO = 0.8d;

  private static final Logger LOGGER = LoggerFactory.getLogger(UsageMeteringService.class);

  private final UsageCounterStore store;
  private final MeterRegistry meterRegistry;
  private final boolean failOpen;
  private final Clock clock;

  @Autowired
  public UsageMeteringService(
      UsageCounterStore store,
      MeterRegistry meterRegistry,
      @Value("${awap.usage.fail-open:false}") boolean failOpen) {
    this(store, meterRegistry, failOpen, Clock.systemUTC());
  }

  public UsageMeteringService(
      UsageCounterStore store, MeterRegistry meterRegistry, boolean failOpen, Clock clock) {
    this.store = store;
    this.meterRegistry = meterRegistry;
    this.failOpen = failOpen;
    this.clock = clock;
  }

  /** Current UTC day; reporting and quota boundaries never depend on the JVM timezone. */
  public LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  /** Effective daily limit: tenant override first, metric default otherwise. */
  public long limitFor(UUID tenantId, UsageMetric metric) {
    return store.limits(tenantId).stream()
        .filter(row -> row.metric() == metric)
        .map(UsageCounterStore.LimitRow::dailyLimit)
        .findFirst()
        .orElse(metric.defaultDailyLimit());
  }

  public Map<UsageMetric, Long> effectiveLimits(UUID tenantId) {
    Map<UsageMetric, Long> limits = new EnumMap<>(UsageMetric.class);
    for (UsageMetric metric : UsageMetric.values()) {
      limits.put(metric, metric.defaultDailyLimit());
    }
    store.limits(tenantId).forEach(row -> limits.put(row.metric(), row.dailyLimit()));
    return limits;
  }

  /**
   * Best-effort metering (fail-open). Returns {@code true} when this delivery was counted and
   * {@code false} for a duplicate, a zero increment or a metering failure.
   */
  @Transactional
  public boolean record(UUID tenantId, UsageMetric metric, long delta, String eventKey) {
    if (delta <= 0) {
      return false;
    }
    try {
      IncrementResult result =
          store.increment(
              new UsageEvent(eventKey, tenantId, metric, today(), delta), UsageMetric.UNLIMITED);
      if (result.outcome() != Outcome.COUNTED) {
        return false;
      }
      observe(metric, delta, limitFor(tenantId, metric), result.value());
      return true;
    } catch (RuntimeException ex) {
      onMeteringFailure(metric, ex);
      return false;
    }
  }

  /**
   * Quota-enforcing metering. Rejects with {@code 429 AWAP-IDN-0006} when the increment would cross
   * the tenant's daily limit, and with {@code 503 AWAP-IDN-0008} on a metering failure unless
   * fail-open mode is enabled.
   */
  @Transactional
  public void reserve(UUID tenantId, UsageMetric metric, long delta, String eventKey) {
    if (delta <= 0) {
      return;
    }

    long limit;
    try {
      limit = limitFor(tenantId, metric);
    } catch (RuntimeException ex) {
      onEnforcementFailure(metric, ex);
      return;
    }

    IncrementResult result;
    try {
      result = store.increment(new UsageEvent(eventKey, tenantId, metric, today(), delta), limit);
    } catch (RuntimeException ex) {
      onEnforcementFailure(metric, ex);
      return;
    }

    switch (result.outcome()) {
      case DUPLICATE -> {
        // A retry or a racing Kafka delivery already counted this event.
      }
      case QUOTA_EXCEEDED -> {
        meterRegistry.counter("awap.usage.quota.exceeded", "metric", metric.key()).increment();
        throw new AwapException(
            ErrorCode.QUOTA_EXCEEDED,
            HttpStatus.TOO_MANY_REQUESTS,
            "Daily quota of "
                + limit
                + " "
                + metric.key()
                + " exceeded; the request was not counted.");
      }
      case COUNTED -> observe(metric, delta, limit, result.value());
    }
  }

  private void observe(UsageMetric metric, long delta, long limit, long value) {
    meterRegistry.counter("awap.usage.increments", "metric", metric.key()).increment(delta);
    if (limit > 0 && value >= Math.ceil(limit * NEAR_LIMIT_RATIO)) {
      meterRegistry.counter("awap.usage.quota.near_limit", "metric", metric.key()).increment();
    }
    // Reaching the limit exactly is "at limit" (near-limit already fired); only a value
    // above the limit is an over-consumption signal.
    if (limit > 0 && value > limit) {
      meterRegistry.counter("awap.usage.quota.exceeded", "metric", metric.key()).increment();
    }
  }

  private void onMeteringFailure(UsageMetric metric, RuntimeException ex) {
    meterRegistry.counter("awap.usage.metering.failures", "metric", metric.key()).increment();
    LOGGER.warn(
        "Usage metering failed for metric {}; the operation continues (fail-open).",
        metric.key(),
        ex);
  }

  private void onEnforcementFailure(UsageMetric metric, RuntimeException ex) {
    meterRegistry.counter("awap.usage.metering.failures", "metric", metric.key()).increment();
    if (failOpen) {
      LOGGER.warn(
          "Usage quota could not be evaluated for metric {}; allowing the request (fail-open).",
          metric.key(),
          ex);
      return;
    }
    LOGGER.error(
        "Usage quota could not be evaluated for metric {}; rejecting the request (fail-closed).",
        metric.key(),
        ex);
    throw new AwapException(
        ErrorCode.USAGE_METERING_UNAVAILABLE,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Usage metering is unavailable; the request was rejected to protect the tenant quota.");
  }
}
