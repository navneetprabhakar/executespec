package com.navneet.executespec.billing.service;

import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.billing.repository.ManagedAiUsagePeriodRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * RR-228 (2026-07-21, P1 first-use reliability) — isolates the first-use INSERT of a managed-AI
 * usage period so a lost insert race cannot poison the caller's transaction.
 *
 * <p>{@code ManagedAiUsagePeriodService.currentOrCreate} runs inside an ambient transaction on
 * every path ({@code authorizeCall}, {@code reserveRunAdmission}, {@code balance}, ...). When two
 * first-use calls for the same {@code (user_id, period_start, period_end)} both find no row and
 * both INSERT, the unique index {@code ux_managed_ai_usage_period_user_period} (V245) rejects the
 * loser. If that INSERT ran in the caller's transaction, the {@code
 * DataIntegrityViolationException} at flush would mark that transaction rollback-only, so even a
 * correct catch-and-reload would fail on its next statement.
 *
 * <p>Running the INSERT in its own {@link Propagation#REQUIRES_NEW} transaction confines the
 * violation: only this inner transaction rolls back, the caller's transaction stays clean, and the
 * caller reloads the winner. {@code saveAndFlush} forces the constraint check to surface here
 * rather than at the outer commit.
 */
@Service
@RequiredArgsConstructor
class ManagedAiUsagePeriodInitializer {

  private final ManagedAiUsagePeriodRepository periodRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  ManagedAiUsagePeriod insertNew(ManagedAiUsagePeriod period) {
    return periodRepository.saveAndFlush(period);
  }
}
