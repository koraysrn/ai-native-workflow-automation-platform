package com.awap.ai.application;

/** Secure backend seam for the AI module (ADR-003). */
public interface AiBackendClient {

  /** Runs a prompt and returns the completion. Blocks until done; timeout is enforced by caller. */
  String complete(String prompt, int maxTokens);
}
