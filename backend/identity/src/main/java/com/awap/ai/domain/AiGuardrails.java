package com.awap.ai.domain;

import java.util.List;
import java.util.Locale;

/**
 * Prompt-injection and jailbreak guardrails. Framework-free so the rules can be unit tested
 * directly; blocked input never reaches a model provider.
 */
public final class AiGuardrails {

  /** Markers that indicate an attempt to override the system prompt or exfiltrate it. */
  public static final List<String> BLOCKED_MARKERS =
      List.of(
          "[inst]",
          "<<sys>>",
          "<|",
          "ignore previous instructions",
          "ignore all previous",
          "disregard previous instructions",
          "system prompt",
          "reveal your instructions",
          "you are now",
          "jailbreak",
          "developer mode",
          "assistant:");

  /** Maximum accepted prompt length before the guardrail rejects it. */
  public static final int MAX_PROMPT_CHARS = 8_000;

  private AiGuardrails() {}

  /**
   * Normalises the prompt and rejects injection markers, empty input and oversized payloads.
   *
   * @throws AiGuardrailViolationException when the prompt must not reach a provider
   */
  public static String sanitize(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      throw new AiGuardrailViolationException("AI prompt must not be empty.");
    }
    String normalized = prompt.strip().replace('\u0000', ' ');
    if (normalized.length() > MAX_PROMPT_CHARS) {
      throw new AiGuardrailViolationException(
          "AI prompt exceeds " + MAX_PROMPT_CHARS + " characters.");
    }
    String lower = normalized.toLowerCase(Locale.ROOT);
    for (String marker : BLOCKED_MARKERS) {
      if (lower.contains(marker)) {
        throw new AiGuardrailViolationException("Prompt injection marker detected: " + marker);
      }
    }
    return normalized;
  }
}
