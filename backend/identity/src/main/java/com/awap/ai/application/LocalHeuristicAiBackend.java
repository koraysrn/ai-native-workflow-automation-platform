package com.awap.ai.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic local provider used when no external LLM is configured. It is intentionally
 * sandboxed: it has no tool access and only performs bounded text transforms, which makes the AI
 * node safe to exercise in dev and in tests without an external model.
 */
@Component
@ConditionalOnProperty(name = "awap.ai.enabled", havingValue = "true", matchIfMissing = true)
public class LocalHeuristicAiBackend implements AiBackendClient {

  @Override
  public String complete(String prompt, int maxTokens) {
    String instruction = prompt.toLowerCase();
    if (instruction.startsWith("summarize:")) {
      String text = prompt.substring("summarize:".length()).trim();
      if (text.length() <= maxTokens * 4) {
        return text;
      }
      return text.substring(0, maxTokens * 4) + "...";
    }
    if (instruction.startsWith("classify:")) {
      String text = prompt.substring("classify:".length()).trim();
      if (text.contains("invoice") || text.contains("payment")) {
        return "FINANCE";
      }
      if (text.contains("contract") || text.contains("agreement")) {
        return "LEGAL";
      }
      return "GENERAL";
    }
    return "";
  }
}
