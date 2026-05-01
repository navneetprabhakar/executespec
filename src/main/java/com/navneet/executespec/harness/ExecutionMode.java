package com.navneet.executespec.harness;

/**
 * Controls speed-vs-quality tradeoffs at the pipeline level.
 *
 * <ul>
 *   <li>{@link #PROTOTYPE} — maximum speed: auto-approved plans, FAST-tier models, syntax-only
 *       validation, reviewer disabled, auto-preview.
 *   <li>{@link #PRODUCTION} — maximum reliability: enforced plan review, CAPABLE-tier models with
 *       escalation, full validation, reviewer + report enabled.
 * </ul>
 */
public enum ExecutionMode {
  PROTOTYPE,
  PRODUCTION;

  public boolean isPrototype() {
    return this == PROTOTYPE;
  }

  public boolean isProduction() {
    return this == PRODUCTION;
  }
}
