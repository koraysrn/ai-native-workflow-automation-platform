package com.awap.engine.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.application.OutboxClaimStore;
import com.awap.engine.domain.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the JPA (fallback) implementation of the outbox lease contract on H2.
 *
 * <p>The assertions filter by event id instead of relying on the batch size, because the shared
 * in-memory database may already contain rows created by other Spring context tests.
 */
@SpringBootTest(classes = com.awap.identity.IdentityApplication.class)
@Transactional
class JpaOutboxClaimStoreTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final Duration LEASE = Duration.ofSeconds(60);
  private static final int MAX_ATTEMPTS = 5;
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private OutboxEventRepository repository;
  @Autowired private EntityManager entityManager;

  private JpaOutboxClaimStore storeAt(Instant instant) {
    return new JpaOutboxClaimStore(repository, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private OutboxEvent persistEvent() {
    OutboxEvent event =
        new OutboxEvent(TENANT, "TASK", UUID.randomUUID(), "TASK_COMPLETED", "{\"ok\":true}");
    return repository.saveAndFlush(event);
  }

  private OutboxEvent reload(OutboxEvent event) {
    return repository.findById(event.getId()).orElseThrow();
  }

  private List<UUID> claimedIds(Instant at) {
    return storeAt(at).claimBatch(1000, LEASE, MAX_ATTEMPTS).stream()
        .map(OutboxClaimStore.ClaimedOutboxEvent::id)
        .toList();
  }

  private OutboxClaimStore.ClaimedOutboxEvent claim(OutboxEvent event, Instant at) {
    return storeAt(at).claimBatch(1000, LEASE, MAX_ATTEMPTS).stream()
        .filter(claimed -> claimed.id().equals(event.getId()))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("expected the event to be claimed: " + event.getId()));
  }

  @Test
  void claimTakesALeaseWithoutPublishing() {
    OutboxEvent event = persistEvent();

    OutboxClaimStore.ClaimedOutboxEvent claimed = claim(event, BASE);

    OutboxEvent reloaded = reload(event);
    assertThat(reloaded.isPublished()).isFalse();
    assertThat(reloaded.getClaimedAt()).contains(BASE);
    assertThat(reloaded.getClaimToken()).contains(claimed.claimToken());
    assertThat(reloaded.getPublishAttempts()).isZero();
    assertThat(reloaded.getPublishedAt()).isEmpty();
  }

  @Test
  void secondRelayCannotClaimWhileTheLeaseIsFresh() {
    OutboxEvent event = persistEvent();
    claim(event, BASE);

    assertThat(claimedIds(BASE.plusSeconds(5))).doesNotContain(event.getId());
  }

  @Test
  void onlyTheLeaseHolderCanMarkTheRowPublishedAndOnlyOnce() {
    OutboxEvent event = persistEvent();
    UUID token = claim(event, BASE).claimToken();

    assertThat(storeAt(BASE).markPublished(event.getId(), UUID.randomUUID())).isFalse();
    assertThat(reload(event).isPublished()).isFalse();

    assertThat(storeAt(BASE).markPublished(event.getId(), token)).isTrue();
    OutboxEvent published = reload(event);
    assertThat(published.isPublished()).isTrue();
    assertThat(published.getPublishedAt()).contains(BASE);
    assertThat(published.getClaimToken()).isEmpty();
    assertThat(published.getClaimedAt()).isEmpty();

    // A second relay replaying the same token cannot publish the row twice.
    assertThat(storeAt(BASE).markPublished(event.getId(), token)).isFalse();
  }

  @Test
  void failedPublishReleasesTheLeaseAndIncrementsAttempts() {
    OutboxEvent event = persistEvent();
    UUID token = claim(event, BASE).claimToken();

    assertThat(storeAt(BASE).release(event.getId(), token)).isTrue();
    OutboxEvent released = reload(event);
    assertThat(released.isPublished()).isFalse();
    assertThat(released.getClaimToken()).isEmpty();
    assertThat(released.getClaimedAt()).isEmpty();
    assertThat(released.getPublishAttempts()).isEqualTo(1);
    assertThat(storeAt(BASE).release(event.getId(), token)).isFalse();
  }

  @Test
  void staleClaimIsReleasedOnceAndRequeuedForRepublication() {
    OutboxEvent event = persistEvent();
    claim(event, BASE);

    List<OutboxClaimStore.ReleasedOutboxClaim> released =
        storeAt(BASE.plusSeconds(125)).releaseStaleClaims(LEASE, 1000);
    assertThat(released)
        .extracting(OutboxClaimStore.ReleasedOutboxClaim::id)
        .contains(event.getId());
    assertThat(
            released.stream()
                .filter(claim -> claim.id().equals(event.getId()))
                .findFirst()
                .orElseThrow()
                .claimAgeSeconds())
        .isEqualTo(125.0d);

    assertThat(storeAt(BASE.plusSeconds(126)).releaseStaleClaims(LEASE, 1000))
        .extracting(OutboxClaimStore.ReleasedOutboxClaim::id)
        .doesNotContain(event.getId());
    assertThat(reload(event).getPublishAttempts()).isEqualTo(1);

    OutboxClaimStore.ClaimedOutboxEvent reclaimed = claim(event, BASE.plusSeconds(126));
    assertThat(reclaimed.claimToken()).isNotNull();
    assertThat(reload(event).isPublished()).isFalse();
  }

  @Test
  void staleClaimIsNotReleasedWhileTheOriginalRelayIsStillSending() {
    OutboxEvent event = persistEvent();
    UUID token = claim(event, BASE).claimToken();

    assertThat(storeAt(BASE.plusSeconds(5)).releaseStaleClaims(LEASE, 1000))
        .extracting(OutboxClaimStore.ReleasedOutboxClaim::id)
        .doesNotContain(event.getId());
    assertThat(reload(event).getClaimToken()).contains(token);
  }

  @Test
  void rowsThatExhaustedTheirAttemptsAreQuarantined() {
    OutboxEvent event = persistEvent();
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      UUID token = claim(event, BASE).claimToken();
      assertThat(storeAt(BASE).release(event.getId(), token)).isTrue();
    }

    assertThat(claimedIds(BASE)).doesNotContain(event.getId());
    assertThat(storeAt(BASE).countExhausted(MAX_ATTEMPTS)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void legacyPublishedRowWithoutTimestampIsReportedAndNeverRepublished() {
    OutboxEvent event = persistEvent();
    repository.markPublished(event.getId(), claim(event, BASE).claimToken(), BASE);
    assertThat(reload(event).getPublishedAt()).isPresent();

    // Force the legacy shape produced by the pre-V44 relay (published without published_at).
    entityManager
        .createQuery("update OutboxEvent e set e.publishedAt = null where e.id = :id")
        .setParameter("id", event.getId())
        .executeUpdate();
    entityManager.clear();

    assertThat(storeAt(BASE).findLegacyPublishedRows(1000))
        .extracting(OutboxClaimStore.LegacyOutboxRow::id)
        .contains(event.getId());
    assertThat(claimedIds(BASE)).doesNotContain(event.getId());
  }
}
