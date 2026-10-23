package com.awap.analytics.application;

import com.awap.analytics.domain.AnalyticsEventType;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Analytics module skeleton (ADR-003). Faz 1 wires the {@code AnalyticsEvent} Avro pipeline and
 * aggregates process metrics.
 */
@Service
public class AnalyticsService {

  private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

  public void record(
      UUID tenantId, AnalyticsEventType type, UUID workflowInstanceId, long durationMs) {
    log.debug(
        "Analytics event: tenant={} type={} instance={} durationMs={}",
        tenantId,
        type,
        workflowInstanceId,
        durationMs);
  }
}
