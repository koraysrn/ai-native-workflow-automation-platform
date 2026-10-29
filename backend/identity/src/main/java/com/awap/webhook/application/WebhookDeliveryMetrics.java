package com.awap.webhook.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Micrometer instruments for the durable webhook retry queue. */
@Component
public class WebhookDeliveryMetrics {

  static final String ATTEMPTS = "awap.webhook.attempts";
  static final String DEAD = "awap.webhook.dead";
  static final String RETRY_LAG = "awap.webhook.retry_lag_seconds";

  private final MeterRegistry meterRegistry;

  public WebhookDeliveryMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** Records one performed attempt, tagged with its classification and HTTP status. */
  public void recordAttempt(String result, int statusCode) {
    meterRegistry
        .counter(ATTEMPTS, "result", result, "status", Integer.toString(statusCode))
        .increment();
  }

  public void recordDead() {
    meterRegistry.counter(DEAD).increment();
  }

  /**
   * Lag between the scheduled instant of a retry and the moment the scheduler actually claimed it.
   */
  public void recordRetryLag(Duration lag) {
    Timer.builder(RETRY_LAG)
        .description("Delay between the scheduled webhook retry instant and the claim instant.")
        .register(meterRegistry)
        .record(lag.isNegative() ? Duration.ZERO : lag);
  }
}
