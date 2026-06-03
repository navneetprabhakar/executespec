package com.navneet.executespec.deployment.preview.verify;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The run-recipe the agent authors for a runnable app, read from {@code
 * .agent-context/run-recipe.json} — the explicit contract that lets {@link PreviewVerifier} boot
 * the app and health-check it without any preview-time heuristic guessing (RL-2026-05-066, preview
 * Phase 1).
 *
 * <p>{@code { "port": 3000, "healthPath": "/health", "startTimeoutSec": 60 }}. All fields are
 * normalised to safe bounds by the compact constructor — in particular {@code startTimeoutSec} is
 * hard-capped at {@link #MAX_START_TIMEOUT_SEC} so a recipe can never ask the boot to run longer
 * than the security ceiling (spec §2: per-call timeout, hard ceiling 120s).
 */
public record RunRecipe(int port, String healthPath, int startTimeoutSec) {

  public static final int DEFAULT_PORT = 3000;
  public static final String DEFAULT_HEALTH_PATH = "/";
  public static final int DEFAULT_START_TIMEOUT_SEC = 60;

  /** Hard ceiling on the boot timeout — a recipe cannot exceed this (security: bounded boot). */
  public static final int MAX_START_TIMEOUT_SEC = 120;

  public RunRecipe {
    if (port <= 0 || port > 65535) port = DEFAULT_PORT;
    if (healthPath == null || healthPath.isBlank()) healthPath = DEFAULT_HEALTH_PATH;
    if (startTimeoutSec <= 0) startTimeoutSec = DEFAULT_START_TIMEOUT_SEC;
    if (startTimeoutSec > MAX_START_TIMEOUT_SEC) startTimeoutSec = MAX_START_TIMEOUT_SEC;
  }

  /**
   * Parse a recipe from the JSON object found in {@code .agent-context/run-recipe.json}. Missing
   * fields fall back to defaults; the compact constructor clamps everything to safe bounds. Returns
   * null only when {@code node} itself is null (caller treats that as "no recipe").
   */
  public static RunRecipe fromJson(JsonNode node) {
    if (node == null || node.isNull()) return null;
    return new RunRecipe(
        node.path("port").asInt(DEFAULT_PORT),
        node.path("healthPath").asText(DEFAULT_HEALTH_PATH),
        node.path("startTimeoutSec").asInt(DEFAULT_START_TIMEOUT_SEC));
  }
}
