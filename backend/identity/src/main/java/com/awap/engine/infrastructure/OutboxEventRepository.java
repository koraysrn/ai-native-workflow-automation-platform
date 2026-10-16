package com.awap.engine.infrastructure;

import com.awap.engine.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for durable outbox events. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
