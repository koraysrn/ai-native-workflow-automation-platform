package com.awap.engine.infrastructure;

import com.awap.engine.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for durable outbox events. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  List<OutboxEvent> findTop100ByPublishedFalseOrderByCreatedAtAsc();

  /**
   * Atomically claims an unpublished event. Returns 1 when this caller won the claim, 0 when the
   * event was already claimed by a concurrent relay run.
   */
  @Modifying
  @Query("update OutboxEvent e set e.published = true where e.id = :id and e.published = false")
  int claim(@Param("id") UUID id);

  /** Releases a claimed event so the next relay sweep retries it. */
  @Modifying
  @Query("update OutboxEvent e set e.published = false where e.id = :id")
  int release(@Param("id") UUID id);

  /** Deletes published events older than the given instant (outbox cleanup). */
  @Modifying
  @Query("delete from OutboxEvent e where e.published = true and e.createdAt < :before")
  int deletePublishedBefore(@Param("before") Instant before);

  /** Returns published events older than the given instant so they can be archived first. */
  @Query("select e from OutboxEvent e where e.published = true and e.createdAt < :before")
  List<OutboxEvent> findPublishedBefore(@Param("before") Instant before);
}
