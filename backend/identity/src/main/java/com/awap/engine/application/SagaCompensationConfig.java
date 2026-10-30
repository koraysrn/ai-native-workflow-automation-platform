package com.awap.engine.application;

/**
 * Parsed {@code compensation} block from a node's config JSON.
 *
 * <p>Only actions with a declared compensation are recorded as saga steps; an integration that does
 * not define one stays outside the saga, which is why {@code docs/saga-compensation.md} lists the
 * integrations that must declare it.
 */
public record SagaCompensationConfig(
    String type,
    String url,
    String payload,
    int timeoutSeconds,
    int maxAttempts,
    long retryBackoffMs) {}
