package com.navneet.executespec.integration;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The single authority on whether a run may start executing without a human plan-approval decision.
 *
 * <p>RR-241: {@code RunRequest.skipApproval} used to travel from the public submit API straight
 * into {@link RunOrchestrator}, where a {@code true} bit disabled the PRODUCTION plan gate for any
 * caller. The gate is the cost/governance checkpoint that stands between a natural-language spec
 * and real provider spend plus a pushed pull request, so an ordinary customer must never be able to
 * disable it by setting a request field.
 *
 * <p>Bypass authority is now a property of the <em>submitting surface</em>, not of the request
 * body. A run may skip the gate only when it originates from a server-internal automation surface
 * that has its own separate authorization step:
 *
 * <ul>
 *   <li>{@code finding:*} — the {@code FindingPatchSubmitter} chokepoint. The human decision
 *       already happened on the finding (PR-review autofix, tech-debt fix, code-review severity
 *       bucket) or, for the CI webhook, behind an operator feature flag with a per-PR attempt cap.
 *   <li>Inbound integration source types — org-admin-configured webhook automation. These also run
 *       in {@code PROTOTYPE} mode and must carry a configured runner identity ({@code
 *       IntegrationManager} fails closed without one).
 *   <li>Benchmark / operator harness surfaces — SUPER_ADMIN + profile-gated endpoints that never
 *       publish to a customer repository.
 *   <li>{@code EXECUTION_VIEW_APPROVED} — the legacy per-solution executor, which is reachable only
 *       after the Launch's own durable plan approval has been proven.
 * </ul>
 *
 * <p>Everything else — {@code REST}, {@code FORK}, {@code AGENT_ORCHESTRATOR}, chat, or any unknown
 * or caller-invented source — fails closed to "gate required", regardless of the {@code
 * skipApproval} bit.
 *
 * <p>The source string is itself caller-supplied on {@code POST /api/runs}, so {@link
 * #isReservedAutomationSource(String)} exists for the API layer to reject a customer trying to
 * impersonate one of these surfaces. Both halves are required: the allowlist alone would be
 * spoofable, and the API rejection alone would not cover other entry points.
 */
@Component
public class PlanGateBypassPolicy {

  /** Prefix of the four customer-facing autofix surfaces (see {@code FindingPatchSubmitter}). */
  private static final String FINDING_PREFIX = "FINDING:";

  /**
   * Exact source tokens that carry their own authorization. Compared case-insensitively after
   * trimming.
   */
  private static final Set<String> TRUSTED_EXACT =
      Set.of(
          // Operator-only benchmark/eval harnesses (SUPER_ADMIN + profile gated).
          "BENCHMARK",
          "SWEBENCH",
          "PROGRAMBENCH",
          "TERMINAL_BENCH",
          "TIER_EVAL",
          // Legacy Execution-View per-solution executor, dispatched only after the Launch's own
          // durable plan approval has been verified (see ExecutionOrchestrator).
          "EXECUTION_VIEW_APPROVED");

  /** Inbound integration adapters (org-admin configured; see {@code IntegrationManager}). */
  private static final Set<String> TRUSTED_INTEGRATIONS =
      Set.of(
          "SLACK",
          "JIRA",
          "CONFLUENCE",
          "PAGERDUTY",
          "SERVICENOW",
          "BITBUCKET",
          "GITHUB",
          "GENERIC",
          "WEBHOOK");

  /**
   * True when a run submitted from {@code source} is allowed to bypass the human plan gate.
   *
   * <p>Fails closed on null/blank/unknown sources.
   */
  public boolean bypassAuthorized(String source) {
    String normalized = normalize(source);
    if (normalized == null) return false;
    if (normalized.startsWith(FINDING_PREFIX)) return true;
    return TRUSTED_EXACT.contains(normalized) || TRUSTED_INTEGRATIONS.contains(normalized);
  }

  /**
   * True when {@code source} names one of the automation surfaces above. The public submit API must
   * reject these so a customer cannot claim automation authority by setting {@code source} in the
   * request body.
   */
  public boolean isReservedAutomationSource(String source) {
    return bypassAuthorized(source);
  }

  private static String normalize(String source) {
    if (source == null) return null;
    String trimmed = source.trim();
    if (trimmed.isEmpty()) return null;
    return trimmed.toUpperCase(Locale.ROOT);
  }
}
