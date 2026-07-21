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
   * {@code update} (EAS OTA push) is deliberately excluded — it is a deploy-equivalent path and
   * must route through the managed {@code /api/deployments} endpoint, not shell.
   */
  static final Set<String> EAS = Set.of("build", "submit");
}
