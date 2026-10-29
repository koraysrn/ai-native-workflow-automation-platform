package com.awap.engine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Domain invariants of the outbox lease (#121): a claim is never a publication. */
class OutboxEventLeaseTest {

  private static final UUID TENANT = UUID.randomUUID();

  private static OutboxEvent newEvent() {
    return new OutboxEvent(TENANT, "TASK", UUID.randomUUID(), "TASK_COMPLETED", "{\"ok\":true}");
  }

  @Test
  void newEventIsUnpublishedUnclaimedAndWithoutAttempts() {
    OutboxEvent event = newEvent();

    assertThat(event.isPublished()).isFalse();
    assertThat(event.getClaimedAt()).isEmpty();
    assertThat(event.getClaimToken()).isEmpty();
    assertThat(event.getPublishedAt()).isEmpty();
    assertThat(event.getPublishAttempts()).isZero();
  }

  @Test
  void claimRecordsTheLeaseWithoutPublishing() {
    OutboxEvent event = newEvent();
    UUID token = UUID.randomUUID();
    Instant claimedAt = Instant.parse("2026-01-01T00:00:00Z");

    event.claim(token, claimedAt);

    assertThat(event.getClaimToken()).contains(token);
    assertThat(event.getClaimedAt()).contains(claimedAt);
    assertThat(event.isPublished()).isFalse();
    assertThat(event.getPublishedAt()).isEmpty();
  }

  @Test
  void publishingClearsTheLeaseAndRecordsTheTimestamp() {
    OutboxEvent event = newEvent();
    event.claim(UUID.randomUUID(), Instant.now());
    Instant publishedAt = Instant.parse("2026-01-01T00:00:05Z");

    event.markPublished(publishedAt);

    assertThat(event.isPublished()).isTrue();
    assertThat(event.getPublishedAt()).contains(publishedAt);
    assertThat(event.getClaimToken()).isEmpty();
    assertThat(event.getClaimedAt()).isEmpty();
  }

  @Test
  void releasingAClaimCountsTheAttempt() {
    OutboxEvent event = newEvent();
    event.claim(UUID.randomUUID(), Instant.now());

    event.releaseClaim();

    assertThat(event.isPublished()).isFalse();
    assertThat(event.getClaimToken()).isEmpty();
    assertThat(event.getClaimedAt()).isEmpty();
    assertThat(event.getPublishAttempts()).isEqualTo(1);

    event.claim(UUID.randomUUID(), Instant.now());
    event.releaseClaim();
    assertThat(event.getPublishAttempts()).isEqualTo(2);
  }

  @Test
  void anAlreadyPublishedEventCannotBeClaimedAgain() {
    OutboxEvent event = newEvent();
    event.markPublished();

    assertThatThrownBy(() -> event.claim(UUID.randomUUID(), Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already published");
  }
}
