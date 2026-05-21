package com.navneet.executespec.eval.v2;

/**
 * Why a tier-eval call did not produce a useful result. Distinct from the case-level "did the
 * output pass validation" judgment — these are runtime classes that prevent any judgment.
 */
public enum TierEvalFailureMode {
  /** Call succeeded and the validator ran. */
  OK,
  /** Provider returned non-200 within the per-call deadline (rate limit, auth, schema reject). */
  PROVIDER_ERROR,
  /** Wall-clock deadline exceeded before the call returned. */
  TIMEOUT,
  /** The model emitted a tool call whose arguments did not parse as JSON. */
  MALFORMED_TOOL_CALL,
  /**
   * The model called a tool that wasn't in the case's tool list, or did not call any tool at all
   * when the case required one.
   */
  WRONG_TOOL,
  /** Unexpected runtime exception (NPE, IO error, classpath issue, etc.). */
  RUNTIME_ERROR
}
