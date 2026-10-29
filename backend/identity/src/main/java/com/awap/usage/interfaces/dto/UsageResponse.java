package com.awap.usage.interfaces.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Tenant usage report for an inclusive UTC day window. */
public record UsageResponse(
    UUID tenantId, LocalDate from, LocalDate to, List<UsageMetricResponse> metrics) {}
