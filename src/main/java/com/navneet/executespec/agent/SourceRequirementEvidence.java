package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Selects byte-preserving source clauses for one planned task's assigned requirements. */
public final class SourceRequirementEvidence {

  private static final Pattern CLAUSE_START =
      Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+|#{1,6}\\s+).*");

  private SourceRequirementEvidence() {}

  /**
   * Returns whole, untruncated source lines for every requirement assigned to {@code task}. If even
   * one assigned requirement cannot be mapped by exact normalized containment, returns the complete
   * source verbatim rather than silently dropping a clause.
   */
  public static String taskScopedClauses(String source, Plan plan, PlannedTask task) {
    if (source == null || source.isBlank()) return "";
    if (plan == null
        || task == null
        || plan.requirements() == null
        || plan.requirements().isEmpty()
        || task.meetsRequirements() == null
        || task.meetsRequirements().isEmpty()) return source;

    Set<String> assigned = new LinkedHashSet<>(task.meetsRequirements());
    List<RequirementSpec> requirements =
        plan.requirements().stream()
            .filter(requirement -> requirement != null && assigned.contains(requirement.id()))
            .toList();
    if (requirements.size() != assigned.size()) return source;

    List<SourceClause> clauses = sourceClauses(source);
    LinkedHashSet<Integer> selected = new LinkedHashSet<>();
    for (RequirementSpec requirement : requirements) {
      Set<Integer> matches = exactMatches(requirement.text(), clauses);
      if (matches.isEmpty()) return source;
      selected.addAll(matches);
    }
    if (selected.isEmpty()) return source;

    return selected.stream()
        .sorted()
        .map(index -> clauses.get(index).text())
        .reduce((left, right) -> left + "\n" + right)
        .orElse(source);
  }

  private static Set<Integer> exactMatches(String requirement, List<SourceClause> clauses) {
    if (requirement == null || requirement.isBlank()) return Set.of();
    String normalizedRequirement = normalize(requirement);
    if (normalizedRequirement.isBlank()) return Set.of();
    LinkedHashSet<Integer> matches = new LinkedHashSet<>();
    for (SourceClause clause : clauses) {
      String normalizedClause = clause.normalized();
      if (normalizedClause.contains(normalizedRequirement)
          || (normalizedClause.length() >= 12
              && normalizedRequirement.contains(normalizedClause))) {
        matches.add(clause.index());
      }
    }
    return Set.copyOf(matches);
  }

  private static List<SourceClause> sourceClauses(String source) {
    List<SourceClause> clauses = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : source.split("\\R", -1)) {
      if (line.isBlank()) {
        appendClause(clauses, current);
        continue;
      }
      if (CLAUSE_START.matcher(line).matches() && !current.isEmpty()) {
        appendClause(clauses, current);
      }
      if (!current.isEmpty()) current.append('\n');
      current.append(line.stripTrailing());
    }
    appendClause(clauses, current);
    if (clauses.isEmpty()) clauses.add(new SourceClause(0, source, normalize(source)));
    return List.copyOf(clauses);
  }

  private static void appendClause(List<SourceClause> clauses, StringBuilder current) {
    if (current.isEmpty()) return;
    String text = current.toString();
    clauses.add(new SourceClause(clauses.size(), text, normalize(text)));
    current.setLength(0);
  }

  private static String normalize(String value) {
    return value == null
        ? ""
        : value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_/{}.-]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
  }

  private record SourceClause(int index, String text, String normalized) {}
}
