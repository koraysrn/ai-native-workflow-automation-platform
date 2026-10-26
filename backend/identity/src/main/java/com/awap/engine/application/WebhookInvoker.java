package com.awap.engine.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.engine.domain.NodeExecution;
import com.awap.engine.domain.WorkflowInstance;
import com.awap.engine.infrastructure.JitteredBackOff;
import com.awap.webhook.infrastructure.WebhookClient;
import com.awap.workflow.domain.NodeDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Executes a WEBHOOK node: validates the target, signs the payload, retries transient failures with
 * the shared 1s/5s/25s jittered backoff and forwards a stable idempotency key so a receiver can
 * de-duplicate retries caused by recovery.
 */
@Component
public class WebhookInvoker {

  private static final Logger log = LoggerFactory.getLogger(WebhookInvoker.class);

  private final WebhookClient webhookClient;
  private final NodeConfigReader configReader;
  private final Sleeper sleeper;

  @Autowired
  public WebhookInvoker(WebhookClient webhookClient, ObjectMapper objectMapper) {
    this(webhookClient, new NodeConfigReader(objectMapper), Sleeper.system());
  }

  WebhookInvoker(WebhookClient webhookClient, NodeConfigReader configReader, Sleeper sleeper) {
    this.webhookClient = webhookClient;
    this.configReader = configReader;
    this.sleeper = sleeper;
  }

  String invoke(WorkflowInstance instance, NodeDefinition node, NodeExecution execution) {
    String url = configReader.readWebhookUrl(node);
    if (url == null) {
      throw new AwapException(
          ErrorCode.WORKFLOW_INVALID_DEFINITION,
          HttpStatus.BAD_REQUEST,
          "WEBHOOK node '" + node.getNodeId() + "' requires a url in its config.");
    }
    String payload = configReader.readWebhookPayload(node);
    Duration timeout = Duration.ofSeconds(Math.max(1, configReader.readTimeoutSeconds(node)));
    String idempotencyKey = idempotencyKey(instance, node, execution);
    int maxAttempts = configReader.readMaxAttempts(node);
    BackOffExecution backOff = new JitteredBackOff().start();

    RuntimeException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        String response = webhookClient.post(url, payload, idempotencyKey, timeout);
        return response == null ? "{}" : response;
      } catch (RuntimeException ex) {
        last = ex;
        log.warn(
            "WEBHOOK node {} attempt {}/{} failed: {}",
            node.getNodeId(),
            attempt,
            maxAttempts,
            ex.getMessage());
        if (attempt < maxAttempts) {
          long delay = backOff.nextBackOff();
          sleeper.sleep(delay == BackOffExecution.STOP ? 1000L : delay);
        }
      }
    }
    throw last == null
        ? new AwapException(
            ErrorCode.GENERIC_INTERNAL_ERROR,
            HttpStatus.BAD_GATEWAY,
            "WEBHOOK node failed without a provider error.")
        : last;
  }

  private String idempotencyKey(
      WorkflowInstance instance, NodeDefinition node, NodeExecution execution) {
    return instance.getId() + ":" + node.getNodeId() + ":" + execution.getId();
  }

  /** Test seam so retry tests do not sleep for real. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis);

    static Sleeper system() {
      return millis -> {
        try {
          Thread.sleep(millis);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Webhook retry sleep interrupted.", ex);
        }
      };
    }
  }
}
