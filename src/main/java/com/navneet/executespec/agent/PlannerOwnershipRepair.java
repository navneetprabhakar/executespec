package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic requirement-ownership backfill for FULL_PLAN outlines.
 *
 * <p>Requirement ownership ({@code meetsRequirements}) is emitted by the model, but Anthropic (and
 * others) treat a nested {@code required} array as a soft hint, so the outline planner
 * intermittently returns tasks with empty ownership even when the tasks themselves are correct —
 * and the scope gate then fails the whole run as "invented deliverables". Observed live on a
 * FastAPI link-shortener spec: identical requests, one run emitted ownership and planned, the next
 * omitted it and died. That makes planning a coin flip.
 *
 * <p>This repair removes the model dependency: when a task has no ownership, it is assigned the
 * requirement(s) it best matches by keyword overlap (title + description vs requirement text), and
 * every requirement is guaranteed an owner. Ownership the model DID provide is preserved untouched.
 * For FULL_PLAN this metadata drives coverage accounting only (there is no expander fan-out), so an
 * approximate-but-complete assignment is strictly better than failing a valid plan.
 */
final class PlannerOwnershipRepair {
  private PlannerOwnershipRepair() {}

  private static final Set<String> STOP =
      Set.of(
          "the",
          "and",
          "for",
          "with",
          "that",
          "this",
          "from",
          "into",
          "your",
          "must",
          "should",
          "when",
          "then",
          "task",
          "tasks",
          "implement",
          "implements",
          "create",
          "creates",
          "build",
          "builds",
          "endpoint",
          "endpoints",
          "service",
          "support",
          "using",
          "returns",
          "return",
          "request",
          "response",
          "value",
          "values",
          "each",
          "every",
          "only",
          "also",
          "which");

  /** Returns a plan in which every task owns >=1 requirement and every requirement has an owner. */
  static Plan assignMissingOwnership(Plan plan) {
    if (plan == null
        || plan.requirements() == null
        || plan.requirements().isEmpty()
        || plan.tasks() == null
        || plan.tasks().isEmpty()) {
      return plan;
    }
    List<RequirementSpec> reqs = plan.requirements();
    List<PlannedTask> tasks = plan.tasks();

    List<Set<String>> taskTokens = new ArrayList<>();
    for (PlannedTask t : tasks) {
      taskTokens.add(tokens(safe(t.title()) + " " + safe(t.description())));
    }
    List<Set<String>> reqTokens = new ArrayList<>();
    for (RequirementSpec r : reqs) {
      reqTokens.add(tokens(r == null ? "" : safe(r.text())));
    }

    // Mutable working copy of current ownership (preserve whatever the model already provided).
    List<Set<String>> owned = new ArrayList<>();
    for (PlannedTask t : tasks) {
      owned.add(
          new LinkedHashSet<>(t.meetsRequirements() == null ? List.of() : t.meetsRequirements()));
    }

    // Pass 1: every requirement must have an owner — assign each currently-unowned requirement to
    // its
    // best-matching task.
    for (int ri = 0; ri < reqs.size(); ri++) {
      String rid = reqs.get(ri) == null ? null : reqs.get(ri).id();
      if (rid == null || rid.isBlank()) continue;
      int riCopy = ri;
      boolean alreadyOwned = owned.stream().anyMatch(s -> ownsCandidate(s, reqs, riCopy));
      if (alreadyOwned) continue;
      owned.get(bestIndex(reqTokens.get(ri), taskTokens)).add(rid);
    }

    // Pass 2: every task must own >=1 requirement — assign each still-empty task its best
    // requirement.
    for (int ti = 0; ti < tasks.size(); ti++) {
      if (!owned.get(ti).isEmpty()) continue;
      String rid = reqs.get(bestIndex(taskTokens.get(ti), reqTokens)).id();
      if (rid != null && !rid.isBlank()) owned.get(ti).add(rid);
    }

    List<PlannedTask> rebuilt = new ArrayList<>();
    for (int ti = 0; ti < tasks.size(); ti++) {
      rebuilt.add(withOwnership(tasks.get(ti), List.copyOf(owned.get(ti))));
    }
    return plan.toBuilder().tasks(rebuilt).build();
  }

  private static boolean ownsCandidate(Set<String> ownership, List<RequirementSpec> reqs, int ri) {
    String rid = reqs.get(ri) == null ? null : reqs.get(ri).id();
    return rid != null && ownership.contains(rid);
  }

  /** argmax token overlap; ties and all-zero overlaps resolve to the lowest index. */
  private static int bestIndex(Set<String> needle, List<Set<String>> haystacks) {
    int best = 0;
    int bestScore = -1;
    for (int i = 0; i < haystacks.size(); i++) {
      int score = overlap(needle, haystacks.get(i));
      if (score > bestScore) {
        bestScore = score;
        best = i;
      }
    }
    return best;
  }

  private static int overlap(Set<String> a, Set<String> b) {
    int n = 0;
    for (String t : a) if (b.contains(t)) n++;
    return n;
  }

  private static Set<String> tokens(String text) {
    Set<String> out = new LinkedHashSet<>();
    for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (raw.length() >= 4 && !STOP.contains(raw)) out.add(raw);
    }
    return out;
  }

  private static PlannedTask withOwnership(PlannedTask t, List<String> meetsRequirements) {
    return PlannedTask.builder()
        .id(t.id())
        .title(t.title())
        .description(t.description())
        .complexity(t.complexity())
        .touchesFiles(t.touchesFiles())
        .dependsOn(t.dependsOn())
        .hardConstraints(t.hardConstraints())
        .meetsRequirements(meetsRequirements)
        .taskType(t.taskType())
        .build();
  }
}
