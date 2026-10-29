package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;
import java.time.Instant;
import java.util.UUID;

/** A delivery row claimed by one scheduler replica and protected by a lease token. */
public record NotificationRetryClaim(
    UUID id,
    UUID tenantId,
    NotificationChannel channel,
    String recipient,
    String templateKey,
    String locale,
    String variablesJson,
    int attempts,
    String status,
    String dedupeKey,
    Instant dueAt,
    Instant lastAttemptAt) {}
