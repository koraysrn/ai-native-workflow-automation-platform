package com.awap.analytics.application;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default sink when OpenSearch is not configured. */
@Component
@ConditionalOnProperty(
    name = "awap.analytics.opensearch.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class NoopAnalyticsEventSink implements AnalyticsEventSink {

  @Override
  public void write(Map<String, Object> document) {
    // Intentionally empty: Micrometer/Prometheus remains the default metrics backend.
  }
}
