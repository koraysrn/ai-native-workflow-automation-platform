package com.awap.ai.domain;

/** Raised when a prompt violates the AI guardrails and must not be sent to a provider. */
public class AiGuardrailViolationException extends RuntimeException {

  public AiGuardrailViolationException(String message) {
    super(message);
  }
}
