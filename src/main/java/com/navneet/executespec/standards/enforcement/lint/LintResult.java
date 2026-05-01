package com.navneet.executespec.standards.enforcement.lint;

import java.util.List;

/**
 * E3.4 — outcome of a single lint adapter invocation.
 *
 * @param tool linter that produced the result (e.g. "eslint")
 * @param status SUCCESS = ran cleanly; FAILED = adapter error; UNAVAILABLE = CLI not on PATH
 *     (graceful degradation)
 * @param violations normalised violation list
 * @param failureReason populated when status != SUCCESS
 */
public record LintResult(
    String tool, Status status, List<LintViolation> violations, String failureReason) {
  public enum Status {
    SUCCESS,
    FAILED,
    UNAVAILABLE
  }

  public boolean ok() {
    return status == Status.SUCCESS;
  }
}
