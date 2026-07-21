package com.navneet.executespec.cost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.api.PageBounds;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Builds the auditable, per-call cost ledger returned by the run cost endpoint. */
@Service
@RequiredArgsConstructor
public class RunCostEvidenceService {

  /** Default number of physical attempts returned per poll. */
  public static final int DEFAULT_STEP_CALLS_PAGE_SIZE = 200;

  /** Hard ceiling for an externally supplied page size. */
  public static final int MAX_STEP_CALLS_PAGE_SIZE = 500;

  /**
   * Reconciliation tolerance floor, matching the audit surface documented in {@code cost/CLAUDE.md}
   * — {@code max($0.000001, 0.5% of known cost)}.
   */
  private static final double ABSOLUTE_TOLERANCE_USD = 0.000001d;

  private static final double RELATIVE_TOLERANCE = 0.005d;

  private static final String SUCCESS = "SUCCESS";

  private final RunStepUsageRepository repository;

  /**
   * Attach cost evidence to the run cost body.
   *
   * @param includeAttemptDetail when false the per-attempt {@code stepCalls} array is omitted
   *     entirely. RR-1552: the physical-attempt ledger carries
   *     orgId/userId/keySource/provider/model and per-attempt spend, which {@code cost/CLAUDE.md}
   *     restricts to own-org ORG_ADMIN and SUPER_ADMIN. Aggregates stay visible to every principal
   *     with run read access.
   * @param page zero-based page of {@code stepCalls}; page 0 is the MOST RECENT attempts
   * @param size page size, bounded by {@link #MAX_STEP_CALLS_PAGE_SIZE}
   */
  public void enrich(
      Map<String, Object> body, PipelineRun run, boolean includeAttemptDetail, int page, int size) {
    // RR-1558 — this endpoint is polled continuously for a live run and a LARGE plan accumulates
    // thousands of physical attempts (primaries, same-provider retries, failover targets). The
    // aggregates come from SQL sums; only the visible page is materialized as entities.
    var pageRequest = PageBounds.request(page, size, MAX_STEP_CALLS_PAGE_SIZE);

    long total = orZero(repository.countByRunId(run.getRunId()));
    // A FAILED attempt is persisted with a hard costUsd=0, and a route with no pricing row is
    // priced at 0 too. Neither is a PROVEN zero-cost call, so both are counted as unknown rather
    // than folded into the aggregate as fact (RR-1556).
    long unknownCostAttempts = orZero(repository.countUnknownCostAttemptsByRunId(run.getRunId()));
    double knownCostLowerBound = orZero(repository.sumKnownCostByRunId(run.getRunId()));

    double runTotal = ((Number) body.getOrDefault("costUsd", 0d)).doubleValue();
    double difference = runTotal - knownCostLowerBound;
    double tolerance = Math.max(ABSOLUTE_TOLERANCE_USD, RELATIVE_TOLERANCE * knownCostLowerBound);

    if (includeAttemptDetail) {
      // Selected newest-first so a live poll always sees the latest attempts, then re-ordered
      // oldest-to-newest WITHIN the page so the array keeps its historical chronological shape.
      List<RunStepUsage> rows =
          new java.util.ArrayList<>(
              repository.findByRunIdOrderByCreatedAtDesc(run.getRunId(), pageRequest).getContent());
      java.util.Collections.reverse(rows);
      body.put("stepCalls", rows.stream().map(RunCostEvidenceService::toCall).toList());
    } else {
      // Empty rather than null: `IdeRunResult` maps over this array without a null guard, and a
      // withheld ledger must not crash the run result page for an ordinary reader.
      // `stepCallsIncluded` + `stepCallsTotal` are what distinguish "withheld" from "none".
      body.put("stepCalls", List.of());
      body.put("stepCallsRestrictedReason", "ORG_ADMIN_OR_SUPER_ADMIN_REQUIRED");
    }
    body.put("stepCallsIncluded", includeAttemptDetail);
    body.put("stepCallsPage", pageRequest.getPageNumber());
    body.put("stepCallsSize", pageRequest.getPageSize());
    body.put("stepCallsTotal", total);
    body.put(
        "stepCallsHasMore",
        (long) pageRequest.getPageNumber() * pageRequest.getPageSize() + pageRequest.getPageSize()
            < total);

    // `attributedCostUsd` is retained for existing consumers but is explicitly a LOWER BOUND: it
    // sums only attempts whose cost is proven. `knownCostUsdLowerBound` is the honest name and
    // carries the same number; `unknownCostAttempts` is what makes the bound legible.
    body.put("attributedCostUsd", knownCostLowerBound);
    body.put("knownCostUsdLowerBound", knownCostLowerBound);
    body.put("unknownCostAttempts", unknownCostAttempts);
    body.put("unattributedCostUsd", difference);
    body.put(
        "costStatus", unknownCostAttempts == 0 ? "KNOWN" : "UNKNOWN_PROVIDER_NOT_REPORTED_PRESENT");
    // RR-1556: never assert full reconciliation over evidence that contains an unpriced or
    // provider-unreported attempt. A matching sum across a ledger with unknown rows proves only
    // that the known rows agree.
    body.put("reconciled", unknownCostAttempts == 0 && Math.abs(difference) <= tolerance);
    body.put("reconciliationToleranceUsd", tolerance);
    body.put(
        "phases",
        Map.of(
            "classifier", nullToZero(run.getClassifierCostUsd()),
            "outline", nullToZero(run.getOutlineCostUsd()),
            "expansion", nullToZero(run.getExpansionCostUsd()),
            "coherence", nullToZero(run.getCoherenceCostUsd())));
  }

  /**
   * RR-1552 — the same predicate the LLM usage audit report enforces: own-org ORG_ADMIN or
   * SUPER_ADMIN. BILLING_ADMIN is deliberately excluded, as are ordinary org developers and
   * cross-org project members, all of whom the run access guard otherwise admits.
   */
  public static boolean canViewPhysicalAttemptDetail(AppUserDetails userDetails, PipelineRun run) {
    if (userDetails == null || run == null) return false;
    AppUser user = userDetails.getUser();
    if (user == null) return false;
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return true;
    String runOrgId = run.getOrgId();
    if (runOrgId == null || runOrgId.isBlank()) {
      // A run with no owning org can only be vouched for by its creator; there is no org admin
      // scope to check against, so nobody else sees attempt-level detail.
      return user.getId() != null && user.getId().equals(run.getCreatedByUserId());
    }
    OrgMember member = userDetails.getOrgMember();
    return member != null
        && runOrgId.equals(user.getOrgId())
        && runOrgId.equals(member.getOrgId())
        && "ORG_ADMIN".equals(member.getOrgRole());
  }

  private static Map<String, Object> toCall(RunStepUsage row) {
    Map<String, Object> call = new LinkedHashMap<>();
    call.put("id", row.getId());
    call.put("taskId", row.getTaskId());
    call.put("agentRole", row.getAgentRole());
    call.put("stepId", row.getStepId());
    call.put("retryOf", row.getRetryOf());
    call.put("llmExecutionContextId", row.getLlmExecutionContextId());
    call.put("orgId", row.getOrgId());
    call.put("userId", row.getUserId());
    call.put("projectId", row.getProjectId());
    call.put("repoId", row.getRepoId());
    call.put("feature", row.getFeature());
    call.put("sourceType", row.getSourceType());
    call.put("sourceId", row.getSourceId());
    call.put("keySource", row.getKeySource());
    call.put("attemptStatus", row.getAttemptStatus());
    call.put("errorCode", row.getErrorCode());
    call.put("error", row.getErrorRedacted());
    call.put("attemptNumber", row.getAttemptNumber());
    call.put("latencyMs", row.getLatencyMs());
    call.put("provider", row.getProvider());
    call.put("model", row.getModel());
    // C1 Step 5 (G5) — causality. Without these a consumer cannot tell a same-provider retry from
    // a cross-provider failover, so a route change looks identical to a repeat of the same route.
    call.put("modelTier", row.getModelTier());
    call.put("attemptKind", row.getAttemptKind());
    call.put("physicalAttemptId", row.getPhysicalAttemptId());
    call.put("parentAttemptId", row.getParentAttemptId());
    call.put("logicalCallId", row.getLogicalCallId());
    call.put("inputTokens", row.getInputTokens());
    call.put("outputTokens", row.getOutputTokens());
    call.put("cacheReadTokens", row.getCacheReadTokens());
    call.put("cacheWriteTokens", row.getCacheWriteTokens());
    call.put("reasoningTokens", row.getReasoningTokens());
    call.put("costUsd", row.getCostUsd());
    // RR-1556 — a row whose cost was never proven reports null rather than a fabricated zero.
    call.put("knownCostUsd", isUnknownCost(row) ? null : row.getCostUsd());
    call.put("costStatus", isUnknownCost(row) ? "UNKNOWN_PROVIDER_NOT_REPORTED" : "KNOWN");
    call.put("callIndex", row.getCallIndex());
    call.put("createdAt", row.getCreatedAt());
    return call;
  }

  /**
   * Mirrors {@code RunStepUsageRepository.countUnknownCostAttemptsByRunId}. A non-SUCCESS attempt
   * is always unknown; a SUCCESS attempt that consumed tokens but priced to exactly zero is the
   * signature of a model missing from the pricing matrix ({@code RunCostTracker.priceCall} returns
   * 0.0 and logs at DEBUG). A genuine zero-token, zero-cost SUCCESS stays KNOWN.
   */
  private static boolean isUnknownCost(RunStepUsage row) {
    if (!SUCCESS.equalsIgnoreCase(row.getAttemptStatus())) return true;
    return row.getCostUsd() == 0d && (row.getInputTokens() > 0 || row.getOutputTokens() > 0);
  }

  private static long orZero(Long value) {
    return value == null ? 0L : value;
  }

  private static double orZero(Double value) {
    return value == null ? 0d : value;
  }

  private static double nullToZero(Double value) {
    return value == null ? 0d : value;
  }
}
