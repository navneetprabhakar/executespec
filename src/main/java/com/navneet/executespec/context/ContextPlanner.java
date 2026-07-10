package com.navneet.executespec.context;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Provider-window aware context budget planner. It is pure apart from the Spring service boundary:
 * callers provide evidence items and receive a deterministic selection plus the reserves required
 * for output, future tools, verification/repair, and provider handoff.
 */
@Service
public class ContextPlanner {
  public Plan plan(Window window, List<Item> items) {
    if (window == null) throw new IllegalArgumentException("window is required");
    List<Item> candidates =
        items == null
            ? List.of()
            : items.stream()
                .filter(item -> item != null && item.tokenEstimate() > 0)
                .sorted(Comparator.comparingInt(Item::priority).reversed())
                .toList();
    long usable =
        window.contextWindowTokens()
            - window.maxOutputTokens()
            - window.providerSafetyMarginTokens()
            - window.futureToolReserveTokens()
            - window.verificationReserveTokens()
            - window.handoffReserveTokens();
    long remaining = Math.max(0, usable);
    var selected = new java.util.ArrayList<Item>();
    var omitted = new java.util.ArrayList<Item>();
    for (Item item : candidates) {
      if (item.tokenEstimate() <= remaining) {
        selected.add(item);
        remaining -= item.tokenEstimate();
      } else omitted.add(item);
    }
    return new Plan(
        Math.max(0, usable),
        selected,
        omitted,
        remaining,
        omitted.stream().mapToLong(Item::tokenEstimate).sum());
  }

  public record Window(
      long contextWindowTokens,
      long maxOutputTokens,
      long providerSafetyMarginTokens,
      long futureToolReserveTokens,
      long verificationReserveTokens,
      long handoffReserveTokens) {
    public Window {
      if (contextWindowTokens <= 0)
        throw new IllegalArgumentException("context window must be positive");
    }
  }

  public record Item(
      String id,
      Kind kind,
      String source,
      String repositorySha,
      String path,
      int tokenEstimate,
      int priority,
      boolean fresh) {}

  public enum Kind {
    SYSTEM,
    GOAL,
    PLAN,
    PROJECT_BASELINE,
    TASK_EVIDENCE,
    WORKING_SET,
    JOURNAL,
    EXTERNAL_ARTIFACT
  }

  public record Plan(
      long usableInputTokens,
      List<Item> selected,
      List<Item> omitted,
      long remainingTokens,
      long omittedTokens) {}
}
