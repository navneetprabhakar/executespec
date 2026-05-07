package com.navneet.executespec.agent;

import java.util.List;

import lombok.Builder;

/**
 * The structured plan produced by {@link PlannerAgent}.
 *
 * <p>New in V2 vs V1:
 *
 * <ul>
 *   <li>{@link #buildCommand} — explicit build command declaration. If null, V2's {@link
 *       com.navneet.executespec.smoketest.BuildCommandResolver} falls back to framework
 *       inference.
 *   <li>{@link #smokeTestStrategy} — how the executor verifies each task after merge (Phase 5 owns
 *       the actual enforcement).
 *   <li>{@link PlannedTask#hardConstraints} — per-task constraints injected into the worker system
 *       prompt.
 * </ul>
 *
 * <p>Removed from V1:
 *
 * <ul>
 *   <li>Ownership walls — sequential execution makes them unnecessary.
 *   <li>Subtask decomposition — folded into the worker's iteration budget.
 * </ul>
 */
@Builder
public record Plan(
    String planTitle,
    String planSummary,
    String projectType,
    String buildCommand,
    SmokeTestStrategy smokeTestStrategy,
    RuntimeSurface runtimeSurface,
    List<PlannedTask> tasks,
    Classification classification,
    /**
     * v2.5.11 (Validation v3 / Pillar 1) — spec requirements the planner enumerated. Each task
     * cites which of these IDs it implements via {@link PlannedTask#meetsRequirements()}. Empty
     * list on legacy runs and when {@code validation.v3-strict} is off.
     */
    List<RequirementSpec> requirements) {

  public Plan {
    if (planTitle == null || planTitle.isBlank()) planTitle = "(untitled plan)";
    if (planSummary == null) planSummary = "";
    if (smokeTestStrategy == null) smokeTestStrategy = SmokeTestStrategy.BUILD;
    if (runtimeSurface == null) runtimeSurface = RuntimeSurface.HTTP;
    if (tasks == null) tasks = List.of();
    if (classification == null) classification = Classification.MEDIUM;
    if (requirements == null) requirements = List.of();
  }

  public enum SmokeTestStrategy {
    /** Run the resolved build command; pass iff exit 0. Default. */
    BUILD,
    /** Start a preview and hit a health endpoint. Heavier but catches runtime issues. */
    PREVIEW_HEALTH,
    /** Run the project's test suite. Most thorough, slowest. */
    TEST_SUITE
  }

  /**
   * How the generated code is invoked at runtime. Drives:
   *
   * <ul>
   *   <li>Test-generation strategy (HTTP → live endpoint probe, CLI → stdin/stdout fixtures,
   *       LIBRARY → unit tests only).
   *   <li>Preview rendering (HTTP backend → show Swagger UI; CLI → show terminal replay; LIBRARY →
   *       hide the preview tab).
   *   <li>Planner constraints (HTTP backend → require {@code openapi.yaml}).
   * </ul>
   */
  public enum RuntimeSurface {
    /** Server accepting HTTP traffic (REST, GraphQL, gRPC-web, WebSocket). Default. */
    HTTP,
    /** Command-line program invoked via args / stdin, assertions against stdout + exit code. */
    CLI,
    /** Importable library / package — no standalone entry point. Unit-test surface only. */
    LIBRARY,
    /** Static HTML/JS frontend with no backend. */
    STATIC_WEB,
    /**
     * React Native / Expo mobile app — Android + iOS from one codebase. Test surface is Jest +
     * {@code @testing-library/react-native}; smoke is {@code expo export} + {@code tsc --noEmit}.
     * Live preview in v1 is an Expo-Go QR pairing flow delivered to the user's device, not an
     * inside-infra render — no preview branch fires. Gated behind {@code
     * codegen.react_native.enabled}.
     */
    MOBILE_APP
  }

  /**
   * Returns true when the project is an HTTP backend that should ship an {@code openapi.yaml} spec
   * + Swagger docs route. Used by the planner prompt validator and the preview-tab renderer.
   */
  public boolean requiresOpenApiSpec() {
    if (runtimeSurface != RuntimeSurface.HTTP) return false;
    if (projectType == null) return false;
    String p = projectType.toLowerCase().replace('-', '_');
    return p.contains("spring_boot")
        || p.equals("node_backend")
        || p.equals("python")
        || p.equals("go")
        || p.equals("rust");
  }
}
