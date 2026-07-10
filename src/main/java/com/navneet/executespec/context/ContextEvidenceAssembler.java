package com.navneet.executespec.context;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Deterministically budgets optional handoff evidence without reordering a worker transcript. Goal,
 * diff, reviewer and decision text are mandatory invariants; only independently repeatable list
 * entries are planner-selected. This is deliberately separate from tool-message compaction.
 */
@Component
public class ContextEvidenceAssembler {

  static final int DEFAULT_OPTIONAL_TOKEN_BUDGET = 6_000;

  private final ContextPlanner planner;

  public ContextEvidenceAssembler(ContextPlanner planner) {
    this.planner = planner;
  }

  public Selection select(ContextAgentInput input) {
    if (input == null) return new Selection(List.of(), List.of(), List.of());
    List<ContextPlanner.Item> items = new ArrayList<>();
    add(items, "package", input.newPackagesDetected(), ContextPlanner.Kind.PROJECT_BASELINE, 80);
    add(items, "file", input.newFilesDetected(), ContextPlanner.Kind.WORKING_SET, 70);
    add(items, "worker", input.workerReportSummaries(), ContextPlanner.Kind.TASK_EVIDENCE, 90);
    ContextPlanner.Plan plan =
        planner.plan(
            new ContextPlanner.Window(DEFAULT_OPTIONAL_TOKEN_BUDGET, 0, 0, 0, 0, 0), items);
    List<String> packages = selectedValues(plan, "package", input.newPackagesDetected());
    List<String> files = selectedValues(plan, "file", input.newFilesDetected());
    List<String> workers = selectedValues(plan, "worker", input.workerReportSummaries());
    return new Selection(packages, files, workers);
  }

  private static void add(
      List<ContextPlanner.Item> out,
      String prefix,
      List<String> values,
      ContextPlanner.Kind kind,
      int priority) {
    if (values == null) return;
    for (int i = 0; i < values.size(); i++) {
      String value = values.get(i);
      if (value == null || value.isBlank()) continue;
      out.add(
          new ContextPlanner.Item(
              prefix + ":" + i,
              kind,
              "context-agent",
              null,
              null,
              Math.max(1, (value.length() + 3) / 4),
              priority,
              true));
    }
  }

  private static List<String> selectedValues(
      ContextPlanner.Plan plan, String prefix, List<String> values) {
    return plan.selected().stream()
        .filter(item -> item.id().startsWith(prefix + ":"))
        .map(item -> item.id().substring(prefix.length() + 1))
        .map(Integer::parseInt)
        .sorted()
        .filter(i -> i >= 0 && i < values.size())
        .map(values::get)
        .toList();
  }

  public record Selection(
      List<String> packageIndexes, List<String> fileIndexes, List<String> workerIndexes) {}
}
