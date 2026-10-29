package com.awap.webhook.application;

import com.awap.webhook.domain.WebhookDelivery;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durable retry poller (fixed 5s delay). Claiming happens in its own short transaction so the HTTP
 * attempt never holds a database transaction; the claim is lease-token guarded, therefore any
 * number of replicas may poll concurrently and each row is attempted exactly once per lease.
 */
@Component
@ConditionalOnProperty(
    name = "awap.webhook.retry.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WebhookRetryScheduler {

  private static final Logger log = LoggerFactory.getLogger(WebhookRetryScheduler.class);

  private final WebhookDeliveryService deliveryService;
  private final WebhookDeliveryAttemptRunner attemptRunner;

  public WebhookRetryScheduler(
      WebhookDeliveryService deliveryService, WebhookDeliveryAttemptRunner attemptRunner) {
    this.deliveryService = deliveryService;
    this.attemptRunner = attemptRunner;
  }

  @Scheduled(fixedDelayString = "${awap.webhook.retry.scheduler-delay-ms:5000}")
  public void pollDueDeliveries() {
    runOnce(Instant.now());
  }

  /**
   * Claims and processes one batch.
   *
   * @return the number of deliveries claimed (and therefore attempted) in this cycle
   */
  public int runOnce(Instant now) {
    deliveryService.expireStale(now);
    List<WebhookDelivery> claimed = deliveryService.claimDue(now);
    for (WebhookDelivery delivery : claimed) {
      try {
        attemptRunner.attempt(delivery, now);
      } catch (RuntimeException ex) {
        // One poison row must not stop the rest of the batch; the lease expires and it is retried.
        log.error("Webhook delivery {} attempt crashed.", delivery.getId(), ex);
      }
    }
    return claimed.size();
  }
}
