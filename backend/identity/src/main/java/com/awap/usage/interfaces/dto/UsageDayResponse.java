package com.awap.usage.interfaces.dto;

import java.time.LocalDate;

/** One UTC day of metered usage. */
public record UsageDayResponse(LocalDate day, long value) {}
