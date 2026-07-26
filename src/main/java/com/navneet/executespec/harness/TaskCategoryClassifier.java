package com.navneet.executespec.harness;

import java.util.List;
import java.util.Locale;

import com.navneet.executespec.agent.PlannedTask;

final class TaskCategoryClassifier {

  private TaskCategoryClassifier() {}

  static String classify(PlannedTask task) {
    if (task == null || task.complexity() == null) return "TASK_MEDIUM";
    String text = normalizedText(task);
    String title = task.title() == null ? "" : task.title().toLowerCase(Locale.ROOT);
    if (containsAny(text, "swe-bench", "terminal-bench", "programbench", "benchmark")) {
      return "BENCHMARK_PATCH";
    }
    // Explicit repair intent wins over generic creation verbs, while a greenfield task's normal
    // test requirement must not reclassify the product implementation as a test repair.
    if (containsAny(text, "failing test", "test failure", "fix test", "repair test")) {
      return "TEST_FIX";
    }
    if (containsAny(text, "fix bug", "fix the bug", "regression", "broken", "failing", "crash")
        || containsAny(title, "bug", "fix", "error", "exception", "failure")) {
      return "BUG_FIX";
    }
    if (containsAny(text, "create", "implement", "build", "add", "new", "scaffold", "generate")) {
      return "GREENFIELD_BUILD";
    }
    if (touchesTestFiles(task)
        || containsAny(text, "unit test", "integration test", "pytest", "junit", "jest")) {
      return "TEST_FIX";
    }
    if (containsAny(
        text, "refactor", "cleanup", "clean up", "rename", "extract", "restructure", "decompose")) {
      return "REFACTOR";
    }
    if (containsAny(text, "terminal", "cli", "command-line", "command line", "shell", "script")) {
      return "TERMINAL_TASK";
    }
    if (containsAny(
        text, "read-only", "read only", "investigate", "analyze", "analyse", "explain")) {
      return "READ_ONLY_HELP";
    }
    return values(task.touchesFiles()).isEmpty()
        ? "TASK_" + task.complexity().name()
        : "PROJECT_PATCH";
  }

  static boolean requiresMeaningfulDiff(String category) {
    return category == null || !"READ_ONLY_HELP".equals(category);
  }

  static boolean requiresMeaningfulDiff(String category, PlannedTask task) {
    return !isDocumentationOnly(task) && requiresMeaningfulDiff(category);
  }

  private static boolean isDocumentationOnly(PlannedTask task) {
    List<String> files = values(task == null ? null : task.touchesFiles());
    if (files.isEmpty()) return false;
    return files.stream()
        .map(value -> value.replace('\\', '/').toLowerCase(Locale.ROOT))
        .allMatch(
            value ->
                value.startsWith("docs/")
                    || value.equals("readme.md")
                    || value.endsWith("/readme.md")
                    || value.equals("changelog.md")
                    || value.endsWith("/changelog.md")
                    || value.endsWith(".md")
                    || value.endsWith(".mdx")
                    || value.endsWith(".rst"));
  }

  private static String normalizedText(PlannedTask task) {
    StringBuilder text = new StringBuilder();
    append(text, task.title());
    append(text, task.description());
    values(task.hardConstraints()).forEach(value -> append(text, value));
    values(task.touchesFiles()).forEach(value -> append(text, value));
    return text.toString().toLowerCase(Locale.ROOT);
  }

  private static boolean touchesTestFiles(PlannedTask task) {
    return values(task.touchesFiles()).stream()
        .map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT))
        .anyMatch(
            path ->
                path.contains("/test/")
                    || path.contains("/tests/")
                    || path.contains("__tests__")
                    || path.endsWith("test.java")
                    || path.endsWith("tests.java")
                    || path.endsWith(".test.ts")
                    || path.endsWith(".test.tsx")
                    || path.endsWith(".spec.ts")
                    || path.endsWith(".spec.tsx")
                    || path.endsWith("_test.go")
                    || path.startsWith("test_")
                    || path.endsWith("_test.py"));
  }

  private static boolean containsAny(String text, String... needles) {
    if (text == null || text.isBlank()) return false;
    for (String needle : needles)
      if (needle != null && !needle.isBlank() && text.contains(needle)) return true;
    return false;
  }

  private static void append(StringBuilder text, String value) {
    if (value != null && !value.isBlank()) text.append(' ').append(value);
  }

  private static List<String> values(List<String> values) {
    return values == null ? List.of() : values;
  }
}
