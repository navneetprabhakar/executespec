package com.navneet.executespec.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Rejects greenfield scaffold plans that pre-create production modules owned by later tasks. */
final class PlanTaskOwnershipValidator {

  private PlanTaskOwnershipValidator() {}

  static List<String> validate(List<PlannedTask> tasks) {
    List<String> violations = new ArrayList<>();
    if (tasks == null || tasks.size() < 2) return violations;
    for (int index = 0; index < tasks.size() - 1; index++) {
      PlannedTask scaffold = tasks.get(index);
      if (!looksLikeScaffold(scaffold.title())) continue;
      Set<String> scaffoldSources = productionSources(scaffold.touchesFiles());
      if (scaffoldSources.isEmpty()) continue;
      Set<String> duplicateOwners = new LinkedHashSet<>();
      for (int later = index + 1; later < tasks.size(); later++) {
        for (String file : productionSources(tasks.get(later).touchesFiles())) {
          if (scaffoldSources.contains(file)) duplicateOwners.add(file);
        }
      }
      if (!duplicateOwners.isEmpty()) {
        violations.add(
            "Scaffold task "
                + scaffold.id()
                + " pre-owns production files assigned to later tasks: "
                + duplicateOwners
                + ". Keep scaffolding to manifests/directories/package markers and give each"
                + " production file one primary owner.");
      }
    }
    return violations;
  }

  private static boolean looksLikeScaffold(String title) {
    String value = title == null ? "" : title.toLowerCase(Locale.ROOT);
    return value.contains("scaffold")
        || value.contains("bootstrap project")
        || value.contains("initialize project")
        || value.contains("initialise project")
        || value.contains("project setup");
  }

  private static Set<String> productionSources(List<String> files) {
    Set<String> result = new LinkedHashSet<>();
    if (files == null) return result;
    for (String file : files) {
      if (file == null || file.isBlank()) continue;
      String normalized = file.replace('\\', '/').toLowerCase(Locale.ROOT);
      String name = Path.of(normalized).getFileName().toString();
      if (name.equals("__init__.py") || name.endsWith(".d.ts")) continue;
      if (normalized.matches(".*\\.(py|java|kt|kts|go|rs|cs|rb|php|swift|ts|tsx|js|jsx)$")) {
        result.add(normalized);
      }
    }
    return result;
  }
}
