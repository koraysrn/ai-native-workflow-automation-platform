package com.awap.usage.interfaces.dto;

import java.util.List;

/**
 * Usage of one metric over the requested window.
 *
 * <p>{@code remaining} is {@code limit - total} (never negative) and {@code -1} when the metric has
 * no daily cap.
 */
public record UsageMetricResponse(
    String metric,
    String unit,
    long total,
    long limit,
    long remaining,
    List<UsageDayResponse> days) {}
