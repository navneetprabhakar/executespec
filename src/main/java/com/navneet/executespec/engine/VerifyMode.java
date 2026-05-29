package com.navneet.executespec.engine;

/**
 * Caller-selected verification behaviour (the verify lever). Data, not a code branch — so "plan
 * mode = no verify", "autofix = hard gate", "benchmark = soft" are configuration.
 */
public enum VerifyMode {
  /** Produce the diff; do not verify. */
  NONE,
  /** One-shot, non-blocking nudge to verify (build/test) before completing. */
  SOFT_NUDGE,
  /** The agent cannot complete until verification (build/test) passes. */
  HARD_GATE
}
