package com.navneet.executespec.billing.service;

import java.time.Instant;

import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.billing.repository.ManagedAiUsagePeriodRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * RR-1664 (2026-07-21) — the atomic spend-admission seam for managed-credit runs.
 *
 * <p>{@code ManagedAiUsageService.assertCanStartRun} is the live pre-async gate at {@code POST
 * /api/runs}; RR-186's {@code assertWithinLimits} has no production caller. It used to load the
 * usage period with no row lock, recompute the routing state, and throw only if that state was
 * ALREADY {@code EXHAUSTED}. Nothing was reserved, so N concurrent submissions at the last credit
 * each observed the same remaining allowance and were all admitted — after which every one of them
 * began spending against a grant that had room for one.
 *
 * <p>This gate is the same shape {@link
 * com.navneet.executespec.executioncontext.LlmExecutionBudgetGate} gave provider calls in RR-321:
 * evaluate every dimension while holding a pessimistic row lock, and commit the reservation before
 * the caller is allowed to proceed. Consequences:
 *
 * <ul>
 *   <li><b>Credit admission is now serialized and reserving.</b> Each admitted run consumes {@link
 *       #MIN_ADMISSION_CREDITS} of headroom before {@code submitRun} returns, so the last credit
 *       admits exactly one run.
 *   <li><b>It is a MINIMUM reservation, not a cost estimate.</b> A run's spend is unknowable at
 *       submission. One credit is the smallest debit a successful managed call can produce, so it
 *       is the largest reservation that cannot itself deny a legitimate submission. Mid-run spend
 *       stays bounded by {@code authorizeCall} and the raw-COGS hard stop.
 *   <li><b>The counter self-heals.</b> Every admission reconciles it to {@code max(reserved,
 *       aiCreditsUsed)}, so recorded usage subsumes earlier reservations and a run that never
 *       spends does not permanently consume grant. No release path — and therefore no leak on crash
 *       — is required.
 *   <li><b>The raw-COGS ceiling is serialized, not reserved.</b> Dollars consumed by a future run
 *       are not knowable, so that dimension is still evaluated against recorded usage — but now
 *       under the lock, as one consistent read.
 * </ul>
 *
 * <p>Runs in its own transaction so the reservation commits even if the caller's surrounding
 * transaction later rolls back: an admission that reached the pipeline must not be given back.
 */
@Service
@RequiredArgsConstructor
class ManagedAiAdmissionGate {

  /**
   * Headroom reserved per admitted run. {@code ManagedAiCreditCalculator} debits {@code ceil(...)}
   * for every successful platform-key call, so one credit is the minimum a run that spends anything
   * at all will consume.
   */
  static final long MIN_ADMISSION_CREDITS = 1L;

  private final ManagedAiUsagePeriodRepository periodRepository;
  private final ManagedAiUsagePeriodService periodService;
  private final ManagedAiRoutingPolicy routingPolicy;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void reserveRunAdmission(
      ManagedAiUserContext user, ManagedAiProperties.TierPolicy policy, Instant now) {
    ManagedAiUsagePeriod period = periodService.currentOrCreate(user, policy, now, true);

    // Reconcile first: recorded usage that has overtaken earlier reservations retires them.
    long reserved = Math.max(period.getReservedAiCredits(), period.getAiCreditsUsed());
    long pending = reserved - period.getAiCreditsUsed();

    // The persisted routing state stays denominated in RECORDED usage — a reservation is a claim
    // on headroom, not proof of spend, and must not make an unspent period look exhausted to the
    // balance/notification surfaces.
    periodService.syncState(period, routingPolicy.recomputeState(period));

    // The admission DECISION, by contrast, must see in-flight claims. Evaluating at `pending`
    // rather than `pending + MIN_ADMISSION_CREDITS` is the RR-321 boundary: a reservation that
    // lands exactly ON the grant is admitted (the last credit belongs to someone), and only the
    // next caller — which would exceed it — is refused. With no claims outstanding this is
    // byte-identical to the previous unlocked check.
    String admissionState = routingPolicy.recomputeState(period, pending);
    if (ManagedAiRoutingPolicy.EXHAUSTED.equals(admissionState)) {
      throw new ResponseStatusException(
          HttpStatus.PAYMENT_REQUIRED,
          "Your ES AI credits are exhausted for this billing period. Add your own provider keys"
              + " (BYOK) or upgrade your plan to keep running.");
    }

    period.setReservedAiCredits(reserved + MIN_ADMISSION_CREDITS);
    period.setUpdatedAt(now);
    periodRepository.save(period);
  }
}
