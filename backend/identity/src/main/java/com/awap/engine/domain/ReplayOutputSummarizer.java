package com.awap.engine.domain;

/**
 * Port used by the reducer to turn a recorded node output into a redacted, deterministic summary.
 * Implementations must never throw on malformed input and must never return {@code null}.
 */
public interface ReplayOutputSummarizer {

  ReplayPayloadSummary summarize(String payload);
}
