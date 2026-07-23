package com.navneet.executespec.tool;

import java.util.Set;

/**
 * HIGH-guardrail per-executable verb allowlists for {@link ShellSandboxPolicy}.
 *
 * <p>Pure data, extracted from the policy class so that class stays under the 600-line ceiling
 * enforced by {@code GodClassLineCeilingTest}. Splitting the catalogue out is preferred over adding
 * a god-class exception: the allowlist in that test is meant to shrink, never grow.
 *
 * <p>At the HIGH guardrail level an allowlisted executable is not enough — the first argument must
 * also be in that executable's verb set. These sets are therefore a security boundary, not a
 * convenience list; adding a verb widens what a worker may run. Behaviour is unchanged by the
 * extraction, and the sets remain pinned by {@code ShellSandboxPolicyLevelTest}.
 */
final class ShellGuardrailVerbs {

  private ShellGuardrailVerbs() {}

  /**
   * npm deliberately does NOT include {@code run}. A {@code package.json} script is arbitrary
   * user-authored code, and blocking it is the whole point of the HIGH verb allowlist (pinned by
   * {@code ShellSandboxPolicyLevelTest.highStrictNpmVerbs}). At HIGH, {@code lintProject} / {@code
   * testProject} on a Node project are correctly unavailable rather than defectively unavailable;
   * the detected command is instead reported honestly by {@code ProjectCommandTool}.
   */
  static final Set<String> NPM = Set.of("install", "ci", "build", "test", "start", "run-script");

  /**
   * Superset of npm that adds the JS tools the RN / Expo pipeline invokes ({@code expo}, {@code
   * eas}, {@code tsc}, {@code jest}, {@code create-expo-app}). npx needs its own list because it
   * forwards to arbitrary tools, unlike npm where the verb is a lifecycle name.
   */
  static final Set<String> NPX =
      Set.of(
          "install",
          "ci",
          "build",
          "test",
          "start",
          "run-script",
          "expo",
          "eas",
          "tsc",
          "jest",
          "create-expo-app",
          "create-react-app");

  static final Set<String> MVN =
      Set.of("compile", "test", "package", "verify", "install", "clean", "spring-boot:run");

  static final Set<String> GRADLE =
      Set.of("build", "test", "check", "clean", "assemble", "dependencies");

  /** Lint + format only; no {@code server}. */
  static final Set<String> RUFF = Set.of("check", "format", "rule");

  /** Direct {@code expo <verb>} invocation. */
  static final Set<String> EXPO =
      Set.of("export", "prebuild", "install", "doctor", "run", "run:android", "run:ios");

  /**
   * RR-853 — {@code eas build}/{@code submit}/{@code update} are networked external side effects
   * (paid remote cloud build, app-store submission, OTA push). They must route through the managed
   * {@code /api/deployments} authorization/confirmation/audit boundary, NOT the worker shell, at
   * every guardrail level. The verb allowlist is therefore empty (previously {@code build}/{@code
   * submit} were permitted at HIGH and every EAS verb was permitted at the seeded LOW default), and
   * {@link #EXTERNAL_SIDE_EFFECT_EAS_VERBS} is enforced unconditionally by {@link
   * ShellSandboxPolicy}.
   */
  static final Set<String> EAS = Set.of();

  /** RR-853 — EAS verbs that trigger a networked build/submit/OTA action; denied at all levels. */
  static final Set<String> EXTERNAL_SIDE_EFFECT_EAS_VERBS = Set.of("build", "submit", "update");

  /**
   * RR-853 — returns a rejection reason when {@code tokens} invoke a networked EAS side effect,
   * whether directly ({@code eas build …}) or via npx ({@code npx eas submit …}), or {@code null}
   * for any other command. Level-independent: an external mutation is never in-scope for a worker
   * shell.
   */
  static String externalSideEffectReason(String[] tokens) {
    if (tokens == null || tokens.length == 0) {
      return null;
    }
    String verb = null;
    if ("eas".equals(tokens[0]) && tokens.length >= 2) {
      verb = tokens[1];
    } else if ("npx".equals(tokens[0]) && tokens.length >= 3 && "eas".equals(tokens[1])) {
      verb = tokens[2];
    }
    if (verb != null && EXTERNAL_SIDE_EFFECT_EAS_VERBS.contains(verb)) {
      return "'eas "
          + verb
          + "' is a networked cloud build/submit/OTA action and must route through the managed"
          + " /api/deployments boundary, not the worker shell";
    }
    return null;
  }
}
