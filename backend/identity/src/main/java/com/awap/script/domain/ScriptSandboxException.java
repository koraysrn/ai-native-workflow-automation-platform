package com.awap.script.domain;

/** Raised when a sandboxed script is rejected, fails or exceeds the timeout. */
public class ScriptSandboxException extends RuntimeException {

  private final boolean timeout;

  public ScriptSandboxException(String message) {
    this(message, false);
  }

  public ScriptSandboxException(String message, boolean timeout) {
    super(message);
    this.timeout = timeout;
  }

  public boolean isTimeout() {
    return timeout;
  }
}
