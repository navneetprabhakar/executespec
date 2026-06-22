package com.navneet.executespec.entitlements;

import java.util.List;

/**
 * Read side of T26 — the effective governance policy for a user, assembled from the same sources the
 * enforcement guards use: the free-tier model lock ({@code ModelAccessService} / {@code
 * ModelRegistry}), the per-org cost-approval threshold (M-032), and the GitHub-owner allowlist
 * (M-039). Pure-read; surfaces only what is actually enforced.
 *
 * <p>Honesty constraint: {@code toolPolicy.enforced} is {@code false} because no per-tool gating
 * system exists in the backend yet — the field is forward-compatible, not aspirational. When per-tool
 * gating lands it gains the real rules and {@code enforced} flips to {@code true}.
 *
 * <p>Served at {@code GET /api/v3/governance-policy}. Consumed by the CLI {@code policy} / {@code
 * doctor} surfaces so they show the real policy instead of inferring it.
 */
public record GovernancePolicyResponse(
    String tier,
    ModelPolicy modelPolicy,
    ToolPolicy toolPolicy,
    Double costApprovalThresholdUsd,
    List<String> allowedGithubOwners,
    boolean superAdminBypass) {

  /** The free-tier model lock, expressed as a read contract. */
  public record ModelPolicy(
      boolean freeTierLocked, String freeTierProvider, List<String> freeModels, String note) {}

  /** Per-tool gating. {@code enforced} is false until a tool-policy system exists. */
  public record ToolPolicy(boolean enforced, String note) {}
}
