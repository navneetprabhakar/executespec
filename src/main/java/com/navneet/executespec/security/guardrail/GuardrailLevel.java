package com.navneet.executespec.security.guardrail;

/**
 * Runtime-switchable security posture. See {@code docs/plans/guardrail-levels-execution-plan.md}
 * for the full behaviour matrix per level.
 *
 * <p>Semantics:
 *
 * <ul>
 *   <li>{@link #LOW} — today's behaviour. Every existing check keeps its current threshold;
 *       reject-vs-warn decisions stay as they are. This is the default so the flag is a zero-risk
 *       rollout.
 *   <li>{@link #MEDIUM} — tightens {@link com.navneet.executespec.security.InputSanitizer} and
 *       {@link com.navneet.executespec.security.CodeAbuseScanner} to reject instead of warn; halves
 *       write/deploy/preview rate limits; expands the shell-sandbox deny list; path-allowlists
 *       worker writes to {@code src/}, {@code test/}, {@code public/}, and root config files.
 *   <li>{@link #HIGH} — further tightens every level, adds an 8KB input cap, per-IP global rate
 *       cap, and a file-write dotfile lockdown. Intended as an incident-response knob, not
 *       steady-state.
 * </ul>
 */
public enum GuardrailLevel {
  LOW,
  MEDIUM,
  HIGH;

  /**
   * Parse a string (flag value, request header, etc.) into a level. Invalid or null strings fall
   * back to {@link #LOW} so a misconfigured flag can never escalate beyond current behaviour.
   */
  public static GuardrailLevel parse(String raw) {
    if (raw == null) return LOW;
    try {
      return GuardrailLevel.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return LOW;
    }
  }

  /**
   * @return true if this level is {@link #MEDIUM} or {@link #HIGH}.
   */
  public boolean atLeastMedium() {
    return this != LOW;
  }

  /**
   * @return true if this level is {@link #HIGH}.
   */
  public boolean atLeastHigh() {
    return this == HIGH;
  }
}
