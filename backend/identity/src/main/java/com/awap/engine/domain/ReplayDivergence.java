package com.awap.engine.domain;

import java.util.Objects;
import java.util.Optional;

/** One concrete deviation between a log replay and the persisted engine state. */
public record ReplayDivergence(ReplayDivergenceType type, Optional<String> nodeId, String detail) {

  public ReplayDivergence {
    Objects.requireNonNull(type, "type");
    nodeId = nodeId == null ? Optional.empty() : nodeId;
    detail = detail == null ? "" : detail;
  }

  public static ReplayDivergence of(ReplayDivergenceType type, String nodeId, String detail) {
    return new ReplayDivergence(type, Optional.ofNullable(nodeId), detail);
  }
}
