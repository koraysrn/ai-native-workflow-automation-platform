package com.awap.engine.infrastructure;

import com.awap.engine.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Data access for durable outbox events. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * Candidate unpublished rows for the JPA claim path; lease eligibility is re-checked on claim.
   */
  @Query(
      "select e from OutboxEvent e"
          + " where e.published = false and e.publishAttempts < :maxAttempts"
          + " order by e.createdAt asc")
  List<OutboxEvent> findClaimable(@Param("maxAttempts") int maxAttempts, Pageable pageable);

  /** Backlog gauge source for the outbox health dashboard (issue #130). */
  long countByPublishedFalse();

  /**
   * Takes a lease on one row. The predicate is the lease contract: unpublished, attempts left, and
   * the previous claim either absent or older than the lease. Returns 0 when another relay already
   * holds a live lease, which is how two relays with the same candidate list cannot both publish.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "update OutboxEvent e"
          + " set e.claimedAt = :now, e.claimToken = :token"
          + " where e.id = :id"
          + " and e.published = false"
          + " and e.publishAttempts < :maxAttempts"
          + " and (e.claimedAt is null or e.claimedAt <= :staleBefore)")
  int claim(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("now") Instant now,
      @Param("staleBefore") Instant staleBefore,
      @Param("maxAttempts") int maxAttempts);

  /** Marks the row published only while the caller still holds the lease token. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "update OutboxEvent e"
          + " set e.published = true, e.publishedAt = :now, e.claimedAt = null, e.claimToken = null"
          + " where e.id = :id and e.published = false and e.claimToken = :token")
  int markPublished(@Param("id") UUID id, @Param("token") UUID token, @Param("now") Instant now);

  /** Releases the lease after a failed publish and counts the attempt. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "update OutboxEvent e"
          + " set e.claimedAt = null, e.claimToken = null,"
          + " e.publishAttempts = e.publishAttempts + 1"
          + " where e.id = :id and e.published = false and e.claimToken = :token")
  int releaseClaim(@Param("id") UUID id, @Param("token") UUID token);

  /** Claims whose lease expired while the row is still unpublished. */
  @Query(
      "select e from OutboxEvent e"
          + " where e.published = false and e.claimToken is not null and e.claimedAt <= :staleBefore"
          + " order by e.claimedAt asc")
  List<OutboxEvent> findStaleClaims(@Param("staleBefore") Instant staleBefore, Pageable pageable);

  /** Legacy/partial rows stranded by the pre-V44 relay ({@code published} without a timestamp). */
  @Query(
      "select e from OutboxEvent e"
          + " where e.published = true and e.publishedAt is null"
          + " order by e.createdAt asc")
  List<OutboxEvent> findLegacyPublished(Pageable pageable);

  /** Unpublished rows that consumed every publish attempt and need operator action. */
  @Query(
      "select count(e) from OutboxEvent e"
          + " where e.published = false and e.publishAttempts >= :maxAttempts")
  long countExhausted(@Param("maxAttempts") int maxAttempts);

  /** Deletes published events older than the given instant (outbox cleanup). */
  @Modifying
  @Transactional
  @Query("delete from OutboxEvent e where e.published = true and e.createdAt < :before")
  int deletePublishedBefore(@Param("before") Instant before);

  /** Returns published events older than the given instant so they can be archived first. */
  @Query("select e from OutboxEvent e where e.published = true and e.createdAt < :before")
  List<OutboxEvent> findPublishedBefore(@Param("before") Instant before);
}
