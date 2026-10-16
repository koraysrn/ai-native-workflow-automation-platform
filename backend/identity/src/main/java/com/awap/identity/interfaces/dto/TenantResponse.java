package com.awap.identity.interfaces.dto;

import java.util.UUID;

/** Public representation of a tenant. */
public record TenantResponse(
    UUID id,
    String slug,
    String name,
    String plan,
    int maxUsers,
    int maxWorkflows,
    long maxAiCallsPerMonth,
    boolean active) {}
