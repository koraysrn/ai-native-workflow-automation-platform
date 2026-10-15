package com.awap.form.interfaces.dto;

import java.time.Instant;
import java.util.UUID;

/** Public representation of a form definition. */
public record FormDefinitionResponse(
    UUID id, UUID tenantId, String name, String schemaJson, int version, Instant createdAt) {}
