package com.awap.notification.application;

import com.awap.notification.domain.NotificationChannel;

/**
 * Thin per-channel dispatch adapter.
 *
 * <p>Implementations perform exactly one bounded provider call and return the provider-assigned
 * message id (or {@code null} when the channel has no provider id or the transport is disabled).
 * Failures are thrown as {@link NotificationDeliveryException} with a retry classification so the
 * caller never has to inspect provider-specific exception types; an unclassified runtime exception
 * stays retryable for backwards compatibility.
 */
public interface NotificationChannelAdapter {

  NotificationChannel channel();

  String dispatch(NotificationMessage message);
}
