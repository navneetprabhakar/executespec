package com.navneet.executespec.referral;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-162 — terminal-status referral hook. Extracted from {@code RunOrchestrator.terminate()} so the
 * wire-point logic is unit- testable without spinning up the orchestrator's 43 collaborators.
 *
 * <p>Fires {@link ReferralService#onRefereeFirstReadyRun(String)} only when:
 *
 * <ul>
 *   <li>the run's terminal status is {@code COMPLETE}, and
 *   <li>the run has a non-null {@code createdByUserId} (anonymous + system runs don't credit
 *       anyone).
 * </ul>
 *
 * <p>{@link ReferralService#onRefereeFirstReadyRun} is itself idempotent (granted_at is the
 * once-only marker), so a restart-mid-flight or a terminate-twice race can't double-credit.
 * Exceptions from the referral subsystem are swallowed with a WARN — never fail a terminate on a
 * referral side-effect.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferralCompletionHook {

  /**
   * The terminal status that triggers the grant. Kept package-private so the test can assert it
   * directly without re-stating it.
   */
  static final String TERMINAL_TRIGGER_STATUS = "COMPLETE";

  private final ReferralService referralService;

  /**
   * Apply the hook for a run that has just reached a terminal state.
   *
   * @param runId the run id (for logging only)
   * @param terminalStatus the run's terminal status (e.g. {@code COMPLETE}, {@code FAILED}, {@code
   *     CANCELLED})
   * @param createdByUserId the run's owner — null for anonymous / system runs
   * @return true if a grant attempt was made (regardless of whether it actually credited — that's
   *     owned by ReferralService); false if the hook short-circuited because conditions weren't
   *     met.
   */
  public boolean onTerminalStatus(String runId, String terminalStatus, String createdByUserId) {
    if (!TERMINAL_TRIGGER_STATUS.equals(terminalStatus)) {
      return false;
    }
    if (createdByUserId == null) {
      return false;
    }
    try {
      referralService.onRefereeFirstReadyRun(createdByUserId);
      return true;
    } catch (Exception refErr) {
      log.warn(
          "v3 referral hook failed for run={} owner={} — {}",
          runId,
          createdByUserId,
          refErr.getMessage());
      return true; // Attempt was made; the failure is logged + contained.
    }
  }
}
