package com.awap.common.api;

import java.net.URI;
import java.time.Instant;

/** RFC 7807 Problem Details representation, extended with an AWAP error code. */
public record ProblemDetails(
    URI type,
    String title,
    int status,
    String detail,
    URI instance,
    String code,
    Instant timestamp) {}
