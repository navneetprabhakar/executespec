package com.navneet.executespec.workspacegeneration;

import java.util.List;
import java.util.Locale;

import com.navneet.executespec.agent.PlannedTask;

/** Server-derived task purpose, frozen into the schema-v3 verification policy and digest. */
public enum TaskAcceptanceKind {
  BOOTSTRAP,
  CHANGE,
  REPAIR,
  NON_CODE;

  static TaskAcceptanceKind resolve(PlannedTask task, GreenfieldBaselinePlan baseline) {
    if (baseline.greenfield()) return BOOTSTRAP;
    List<String> paths = task.touchesFiles();
    boolean onlyNonCode =
        !paths.isEmpty()
            && paths.stream()
                .allMatch(
                    path ->
                        path != null
                            && path.toLowerCase(Locale.ROOT)
                                .matches(
                                    ".*\\.(md|txt|rst|adoc|json|ya?ml|toml|properties|css|svg)$"));
    if (onlyNonCode) return NON_CODE;
    String intent = (task.title() + " " + task.description()).toLowerCase(Locale.ROOT);
    return intent.matches("(?s).*\\b(fix|repair|regression|bug)\\b.*") ? REPAIR : CHANGE;
  }
}
