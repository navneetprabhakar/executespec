package com.navneet.executespec.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * v2.5.11 — a single spec requirement the planner extracted from the user's request. Part of the
 * validation v3 requirement-graph: the planner enumerates requirements up-front, each task cites
 * which requirement IDs it covers, and the reviewer later verifies evidence for each requirement.
 *
 * <p>Persistence: serialised to {@code pipeline_run.plan_requirements_json} as a JSON array of
 * these objects. See V109 migration.
 *
 * <p>Kinds:
 *
 * <ul>
 *   <li>FUNCTIONAL — endpoints, features, user-facing behaviour
 *   <li>NON_FUNCTIONAL — auth, perf, reliability, logging
 *   <li>TEST — explicit "add unit tests" / "ensure integration tests pass"
 *   <li>CONSTRAINT — hard constraints (port, library, language)
 * </ul>
 */
public record RequirementSpec(String id, String text, RequirementKind kind) {

  /** Jackson-friendly constructor. Empty text trims to null for robustness. */
  @JsonCreator
  public RequirementSpec(
      @JsonProperty("id") String id,
      @JsonProperty("text") String text,
      @JsonProperty("kind") RequirementKind kind) {
    this.id = id == null ? null : id.trim();
    this.text = text == null ? null : text.trim();
    this.kind = kind == null ? RequirementKind.FUNCTIONAL : kind;
  }

  public enum RequirementKind {
    FUNCTIONAL,
    NON_FUNCTIONAL,
    TEST,
    CONSTRAINT
  }
}
