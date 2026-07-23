package com.navneet.executespec.standards.enforcement;

/**
 * RR-1611 — the availability channel for a single deterministic standard's evaluation.
 *
 * <p>Before this existed, the AST and file-pattern enforcers could only contribute to a violation
 * list, so a degraded check (a typo'd regex, a missing rule parameter, an unsupported rule kind, an
 * unreadable/empty inventory) returned zero violations — structurally indistinguishable from a
 * genuinely clean pass. The run terminated clean with the governance control reported as satisfied.
 *
 * <p>Every deterministic enforcer now reports one of these per standard so the gate can tell "the
 * check ran and found nothing" apart from "the check could not run". A non-{@link #EVALUATED}
 * status is routed to the same {@code SYNTH-STANDARD-UNAVAILABLE-*} path the lint channel already
 * uses, forcing {@code PARTIAL / STANDARDS_ENFORCEMENT_UNAVAILABLE} instead of a silent pass.
 */
public enum EvaluationStatus {
  /**
   * The check ran against a usable inventory and its verdict (violations or none) is trustworthy.
   */
  EVALUATED,
  /** The check could not evaluate — empty or entirely unreadable inventory. */
  UNAVAILABLE,
  /**
   * The standard's rule is misconfigured — missing required parameter, invalid regex, or
   * unsupported kind.
   */
  INVALID_RULE
}
