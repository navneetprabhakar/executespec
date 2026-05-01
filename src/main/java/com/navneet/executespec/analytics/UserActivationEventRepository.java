package com.navneet.executespec.analytics;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Wave 2.1 — activation event store. One row per (user, event) tuple; the composite PK enforces the
 * "first-time" invariant.
 *
 * <p>For cohort queries + time-to-event analytics we delegate to PostHog — this table is the
 * server-side idempotency ledger, not the analytics store.
 */
@Repository
public interface UserActivationEventRepository
    extends JpaRepository<UserActivationEvent, UserActivationEvent.PK> {

  boolean existsByUserIdAndEventName(String userId, String eventName);

  Optional<UserActivationEvent> findByUserIdAndEventName(String userId, String eventName);

  /** Cheap count by event — used by the admin analytics dashboard headline numbers. */
  @Query("SELECT COUNT(e) FROM UserActivationEvent e WHERE e.eventName = :event")
  long countByEventName(@Param("event") String eventName);

  @Query(
      "SELECT COUNT(e) FROM UserActivationEvent e WHERE e.eventName = :event AND e.occurredAt >="
          + " :since")
  long countByEventNameSince(@Param("event") String eventName, @Param("since") Instant since);
}
