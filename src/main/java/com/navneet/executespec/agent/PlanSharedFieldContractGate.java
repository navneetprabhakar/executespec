package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects incompatible field sets declared for the same model or DTO across plan tasks. */
final class PlanSharedFieldContractGate {
  private static final Pattern DECLARATION =
      Pattern.compile(
          "(?i)^\\s*(?:[-*]\\s+)?(?:the\\s+)?(?:\\*\\*|`)?([A-Z][A-Za-z0-9_]+)"
              + "(?:\\*\\*|`)?(?:\\s+(?:model|dto|response|request))?(?:\\s+fields)?\\s*:"
              + "\\s*(.*)$");
  private static final Pattern FIELD =
      Pattern.compile(
          "(?i)(?:^|,\\s*)`?\\s*[\\[{]?\\s*([a-z_][a-z0-9_]*)(?=\\s*(?:\\(|:|,|[\\]}]|`|$))");
  private static final Pattern BULLET_FIELD =
      Pattern.compile("(?i)^\\s*[-*]\\s+`?([a-z_][a-z0-9_]*)`?\\s*(?=\\(|:|[-–—]|$)");
  private static final Pattern INLINE_DECLARATION =
      Pattern.compile(
          "(?im)(?:^|[-*]\\s+|\\bthe\\s+)(?:\\*\\*|`)?([A-Z][A-Za-z0-9_]+)"
              + "(?:\\*\\*|`)?(?:\\s+fields)?\\s*:\\s*([^\\n.]+)");

  private PlanSharedFieldContractGate() {}

  static void addViolations(List<PlannedTask> tasks, List<String> violations) {
    Map<String, Map<String, LinkedHashSet<String>>> byContract = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      extract(task)
          .forEach(
              (contract, fields) ->
                  byContract
                      .computeIfAbsent(contract, ignored -> new LinkedHashMap<>())
                      .computeIfAbsent(task.id(), ignored -> new LinkedHashSet<>())
                      .addAll(fields));
    }
    byContract.forEach((contract, declarations) -> compare(contract, declarations, violations));
  }

  private static Map<String, Set<String>> extract(PlannedTask task) {
    String[] lines = evidence(task).split("\\R", -1);
    Map<String, Set<String>> declarations = new LinkedHashMap<>();
    for (int index = 0; index < lines.length; index++) {
      Matcher header = DECLARATION.matcher(lines[index]);
      if (!header.matches()) continue;
      LinkedHashSet<String> fields = new LinkedHashSet<>(fields(header.group(2)));
      int following = index + 1;
      while (following < lines.length) {
        Matcher bullet = BULLET_FIELD.matcher(lines[following]);
        if (!bullet.find()) break;
        fields.add(bullet.group(1).toLowerCase(Locale.ROOT));
        following++;
      }
      if (fields.size() < 3) continue;
      declarations
          .computeIfAbsent(
              header.group(1).toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>())
          .addAll(fields);
      index = Math.max(index, following - 1);
    }
    Matcher inline = INLINE_DECLARATION.matcher(evidence(task));
    while (inline.find()) {
      Set<String> fields = fields(inline.group(2));
      if (fields.size() < 3) continue;
      declarations
          .computeIfAbsent(
              inline.group(1).toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>())
          .addAll(fields);
    }
    return declarations;
  }

  private static Set<String> fields(String value) {
    LinkedHashSet<String> fields = new LinkedHashSet<>();
    Matcher matcher = FIELD.matcher(safe(value));
    while (matcher.find()) fields.add(matcher.group(1).toLowerCase(Locale.ROOT));
    return fields;
  }

  private static void compare(
      String contract, Map<String, LinkedHashSet<String>> declarations, List<String> violations) {
    List<Map.Entry<String, LinkedHashSet<String>>> entries =
        new ArrayList<>(declarations.entrySet());
    for (int left = 0; left < entries.size(); left++) {
      for (int right = left + 1; right < entries.size(); right++) {
        Map.Entry<String, LinkedHashSet<String>> first = entries.get(left);
        Map.Entry<String, LinkedHashSet<String>> second = entries.get(right);
        Set<String> shared = new LinkedHashSet<>(first.getValue());
        shared.retainAll(second.getValue());
        if (shared.isEmpty() || first.getValue().equals(second.getValue())) continue;
        Set<String> firstOnly = difference(first.getValue(), second.getValue());
        Set<String> secondOnly = difference(second.getValue(), first.getValue());
        violations.add(
            second.getKey()
                + ": shared "
                + contract
                + " field contract differs from "
                + first.getKey()
                + "; first-only="
                + firstOnly
                + ", second-only="
                + secondOnly);
      }
    }
  }

  private static Set<String> difference(Set<String> first, Set<String> second) {
    LinkedHashSet<String> difference = new LinkedHashSet<>(first);
    difference.removeAll(second);
    return difference;
  }

  private static String evidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }
}
