package com.navneet.executespec.compliance;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RR-090 — durable store of retryable GDPR erasure obligations. See {@link
 * PendingErasureObligation}.
 */
public interface PendingErasureObligationRepository
    extends JpaRepository<PendingErasureObligation, String> {

  /** Every open obligation, oldest first, for the scheduled retry sweep. */
  List<PendingErasureObligation> findAllByOrderByRequestedAtAsc();
}
