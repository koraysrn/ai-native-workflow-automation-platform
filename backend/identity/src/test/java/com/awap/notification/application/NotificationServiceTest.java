package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.awap.engine.application.OutboxEventPublisher;
import com.awap.notification.domain.NotificationChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

  private final OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
  private final NotificationTemplateRenderer renderer = mock(NotificationTemplateRenderer.class);

  @Test
  void dispatchesOnceWhenAdapterSucceedsAndReportsSent() {
    when(renderer.render(any(), any())).thenReturn("Hello world");
    CountingAdapter adapter = new CountingAdapter(0);
    NotificationService service = new NotificationService(List.of(adapter), renderer, outbox);

    service.send(
        UUID.randomUUID(), "a@example.com", NotificationChannel.EMAIL, "welcome", null);

    assertThat(adapter.invocations.get()).isEqualTo(1);
    verify(outbox).publish(any(), eq("NOTIFICATION"), any(), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void retriesTransientFailuresThenReportsFailureAndRethrows() {
    when(renderer.render(any(), any())).thenReturn("Hello world");
    CountingAdapter adapter = new CountingAdapter(3);
    NotificationService service = new NotificationService(List.of(adapter), renderer, outbox);

    assertThatThrownBy(
            () ->
                service.send(
                    UUID.randomUUID(),
                    "a@example.com",
                    NotificationChannel.EMAIL,
                    "welcome",
                    null))
        .isInstanceOf(IllegalStateException.class);

    assertThat(adapter.invocations.get()).isEqualTo(3);
    verify(outbox).publish(any(), eq("NOTIFICATION"), any(), eq("NOTIFICATION_FAILED"), any());
  }

  @Test
  void retriesUntilSuccessWithinMaxAttempts() {
    when(renderer.render(any(), any())).thenReturn("Hello world");
    CountingAdapter adapter = new CountingAdapter(1);
    NotificationService service = new NotificationService(List.of(adapter), renderer, outbox);

    service.send(UUID.randomUUID(), "a@example.com", NotificationChannel.EMAIL, "welcome", null);

    assertThat(adapter.invocations.get()).isEqualTo(2);
    verify(outbox).publish(any(), eq("NOTIFICATION"), any(), eq("NOTIFICATION_SENT"), any());
  }

  @Test
  void rejectsUnknownChannel() {
    NotificationService service = new NotificationService(List.of(), renderer, outbox);

    assertThatThrownBy(
            () ->
                service.send(
                    UUID.randomUUID(), "a@example.com", NotificationChannel.SMS, "x", null))
        .isInstanceOf(com.awap.common.api.AwapException.class);
  }

  /** Adapter that fails {@code failCount} times before succeeding. */
  private static final class CountingAdapter implements NotificationChannelAdapter {
    private final int failCount;
    private final AtomicInteger invocations = new AtomicInteger();

    CountingAdapter(int failCount) {
      this.failCount = failCount;
    }

    @Override
    public NotificationChannel channel() {
      return NotificationChannel.EMAIL;
    }

    @Override
    public void dispatch(NotificationMessage message) {
      if (invocations.incrementAndGet() <= failCount) {
        throw new IllegalStateException("provider 5xx");
      }
    }
  }
}
