package com.navneet.executespec.agentsession;

import static com.navneet.executespec.agentsession.AgentIntentDecisions.decision;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;

final class AgentNonRunIntents {

  private AgentNonRunIntents() {}

  static Optional<OrchestratorDecision> decideBeforeCodeChange(
      String message, String lower, InterpretIntentRequest request) {
    if (AgentIntentPatterns.looksLikeLocalDeterministicAction(lower)) {
      TypedAction action =
          new TypedAction(
              "local_tool_action",
              Map.of("message", message),
              true,
              "none",
              "Run this local terminal/git action on the client?");
      return Optional.of(
          decision(
              "LOCAL_TOOL_ACTION",
              0.9,
              "deterministic_local_action",
              true,
              "This should be handled locally by the terminal client, not submitted as a remote"
                  + " run.",
              action,
              false,
              true,
              "Local shell/git/file action must stay in the client."));
    }

    if (AgentIntentPatterns.looksLikeReview(lower)) {
      return Optional.of(
          decision(
              "REVIEW_ONLY",
              0.91,
              "review_request",
              false,
              "I will review and return findings without creating a run.",
              null,
              false,
              false,
              "Review-only requests must not create runs."));
    }

    if (AgentIntentPatterns.looksLikePlan(lower)) {
      return Optional.of(
          decision(
              "PLAN_ONLY",
              0.91,
              "plan_or_how_would_request",
              false,
              "I will write the plan without creating a run.",
              null,
              false,
              false,
              "Planning requests must not create runs."));
    }

    if (AgentIntentPatterns.looksLikeVagueMutation(lower, request)) {
      return Optional.of(
          decision(
              "CLARIFY",
              0.88,
              "mutation_request_missing_target",
              false,
              "What exactly should I change? I need a repo, file, failing test, or error before"
                  + " proposing implementation.",
              null,
              false,
              false,
              "Vague mutation request lacks a safe implementation target."));
    }

    return Optional.empty();
  }

  static Optional<OrchestratorDecision> decideAfterCodeChange(
      String message, String lower, InterpretIntentRequest request) {
    if (AgentIntentPatterns.looksLikeReadOnlyCodeHelp(lower)) {
      return Optional.of(
          decision(
              "READ_ONLY_CODE_HELP",
              0.9,
              "read_only_code_help",
              false,
              "I will inspect or explain this without creating a run.",
              null,
              false,
              false,
              "Read-only code help must not create runs."));
    }

    if (AgentIntentPatterns.containsAny(lower, "cost", "spend", "billing")) {
      return Optional.of(
          decision(
              "RUN_LIFECYCLE_ACTION",
              0.82,
              new TypedAction("cost_query", Map.of("message", message), false, "none", null),
              "cost_or_billing_query",
              "I can answer cost questions from run/session ledgers without creating a new run."));
    }

    if (AgentIntentPatterns.containsAny(lower, "project", "link repo", "this repo")) {
      String gitRemote =
          request == null
                  || request.repoContext() == null
                  || request.repoContext().gitRemote() == null
              ? ""
              : request.repoContext().gitRemote();
      return Optional.of(
          decision(
              "LOCAL_TOOL_ACTION",
              0.76,
              new TypedAction(
                  "resolve_project", Map.of("gitRemote", gitRemote), false, "none", null),
              "resolve_project_context",
              "I will resolve the current repository against linked ExecuteSpec projects."));
    }

    return Optional.empty();
  }
}
