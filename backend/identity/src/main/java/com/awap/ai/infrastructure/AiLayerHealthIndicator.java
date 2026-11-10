package com.awap.ai.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports AI-layer reachability and correlation-ID round-tripping as an actuator health indicator
 * (issue #191). Enabled only when {@code awap.ai.layer.enabled=true}, so a minimal stack without
 * the AI layer does not turn the application health DOWN.
 */
@Component
@ConditionalOnProperty(name = "awap.ai.layer.enabled", havingValue = "true")
public class AiLayerHealthIndicator implements HealthIndicator {

  private final AiLayerClient client;

  public AiLayerHealthIndicator(AiLayerClient client) {
    this.client = client;
  }

  @Override
  public Health health() {
    try {
      AiLayerClient.AiLayerProbe probe = client.probe();
      if (!probe.healthy()) {
        return Health.down()
            .withDetail("status", probe.status())
            .withDetail("correlationId", probe.sentCorrelationId())
            .withDetail("echoedCorrelationId", probe.receivedCorrelationId())
            .build();
      }
      return Health.up()
          .withDetail("status", probe.status())
          .withDetail("correlationId", probe.sentCorrelationId())
          .build();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return Health.down().withDetail("error", "interrupted").build();
    } catch (Exception ex) {
      return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
    }
  }
}
