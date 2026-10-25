package com.awap.ai.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * First AI agent step (ADR-003): summarize / classify with a hard timeout and a cost budget. The
 * service fails closed on missing config, prompt-injection markers and oversized input, so an
 * untrusted payload can never reach an external model or run away in cost.
 */
@Service
public class AiAgentService {

  static final List<String> INJECTION_MARKERS =
      List.of("[INST]", "<|", "ignore previous instructions", "system:", "assistant:");

  private final AiBackendClient backend;
  private final long timeoutMs;
  private final int maxInputChars;
  private final ExecutorService executor;

  @Autowired
  public AiAgentService(
      AiBackendClient backend,
      @Value("${awap.ai.timeout-ms:5000}") long timeoutMs,
      @Value("${awap.ai.max-input-chars:4000}") int maxInputChars) {
    this(backend, timeoutMs, maxInputChars, Executors.newSingleThreadExecutor());
  }

  AiAgentService(
      AiBackendClient backend,
      long timeoutMs,
      int maxInputChars,
      ExecutorService executor) {
    this.backend = backend;
    this.timeoutMs = timeoutMs;
    this.maxInputChars = maxInputChars;
    this.executor = executor;
  }

  public String summarize(String input) {
    return run("summarize:" + input, Math.min(512, input.length()));
  }

  public String classify(String input) {
    return run("classify:" + input, 64);
  }

  private String run(String prompt, int maxTokens) {
    String payload = prompt.substring(prompt.indexOf(':') + 1).trim();
    validate(payload);

    try {
      return executor
          .submit(() -> backend.complete(prompt, maxTokens))
          .get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.GATEWAY_TIMEOUT,
          "AI step timed out after " + timeoutMs + "ms.");
    } catch (AwapException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.BAD_GATEWAY,
          "AI backend failed: " + ex.getMessage());
    }
  }

  private void validate(String payload) {
    if (payload == null || payload.isBlank()) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR, HttpStatus.BAD_REQUEST, "AI input must not be blank.");
    }
    if (payload.length() > maxInputChars) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.PAYLOAD_TOO_LARGE,
          "AI input exceeds the " + maxInputChars + "-character cost cap.");
    }
    for (String marker : INJECTION_MARKERS) {
      if (payload.toLowerCase().contains(marker.toLowerCase())) {
        throw new AwapException(
            ErrorCode.FORBIDDEN,
            HttpStatus.BAD_REQUEST,
            "AI input rejected: prompt-injection pattern detected.");
      }
    }
  }
}
