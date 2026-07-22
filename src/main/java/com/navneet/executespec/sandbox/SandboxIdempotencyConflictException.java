package com.navneet.executespec.sandbox;

/**
 * RR-329 — an idempotency key was reused for a request whose accountable identity or immutable
 * source/isolation policy differs from the sandbox that key already names.
 *
 * <p>The V307 idempotency key is globally unique, so returning the existing row on any duplicate
 * key would hand one request another tenant/run's runtime identity, and would silently treat a
 * changed source revision or isolation policy as an idempotent retry. Both are typed conflicts, not
 * successes: an existing row is returned only for an exact identity match, and every other reuse
 * fails closed here.
 */
public class SandboxIdempotencyConflictException extends RuntimeException {
  public SandboxIdempotencyConflictException(String message) {
    super(message);
  }
}
