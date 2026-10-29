package com.awap.engine.domain;

/**
 * Result of redacting a log payload: only shape information (validity, length, key names and a
 * digest) is retained, never the raw values, so replay reports cannot leak personal data.
 */
public record ReplayPayloadSummary(boolean validJson, String summary) {

  public ReplayPayloadSummary {
    summary = summary == null ? "" : summary;
  }

  public static ReplayPayloadSummary none() {
    return new ReplayPayloadSummary(true, "");
  }

  public static ReplayPayloadSummary invalid(int length) {
    return new ReplayPayloadSummary(false, "unparsed;length=" + length);
  }
}
