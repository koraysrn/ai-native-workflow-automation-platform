package com.awap.engine.interfaces.dto;

import java.util.Optional;

/** Public view of one divergence between the log projection and the persisted state. */
public record ReplayDivergenceView(String type, Optional<String> nodeId, String detail) {}
