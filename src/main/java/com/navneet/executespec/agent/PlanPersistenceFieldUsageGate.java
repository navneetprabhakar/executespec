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

/** Rejects SQL mutations that use fields absent from an explicit cross-task table contract. */
final class PlanPersistenceFieldUsageGate {
  private static final Pattern TABLE_DECLARATION =
      Pattern.compile(
          "(?i)\\b([A-Z][A-Za-z0-9_]*)\\s+(?:table|model)\\s*"
              + "(?::|\\bhas\\s+(?:fields?|columns?)?\\s*|\\b(?:fields?|columns?)\\s*:?\\s*|\\()"
              + "([^)\\n.]+)");
  private static final Pattern MARKDOWN_MODEL_DECLARATION =
      Pattern.compile(
          "(?is)(?:^|\\s+-\\s+)\\*\\*([A-Z][A-Za-z0-9_]*)\\*\\*"
              + "(?:\\s*\\(`[^`]+`\\))?\\s*(?:—|-)\\s*(.*?)"
              + "(?=\\s+-\\s+\\*\\*[A-Z][A-Za-z0-9_]*\\*\\*|\\z)");
  private static final Pattern UPDATE =
      Pattern.compile("(?i)\\bUPDATE\\s+([a-z_][a-z0-9_]*)\\s+SET\\s+([^;\\n.]+)");
  private static final Pattern ASSIGNMENT =
      Pattern.compile("(?i)(?:^|,)\\s*`?([a-z_][a-z0-9_]*)`?\\s*=");
  private static final Pattern FIELD = Pattern.compile("(?i)`?([a-z_][a-z0-9_]*)`?");
  private static final Set<String> NON_FIELDS =
      Set.of(
          "a", "an", "and", "boolean", "columns", "fields", "float", "has", "int", "integer",
          "model", "not", "null", "string", "str", "table", "the", "uuid");
  private static final Set<String> GENERIC_ENTITY_TOKENS =
      Set.of("entry", "event", "log", "model", "record", "row", "table");

  private PlanPersistenceFieldUsageGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    Map<String, TableContract> contracts = explicitTableContracts(plan.tasks());
    if (contracts.isEmpty()) return;
    addNearDuplicateAliasViolations(new ArrayList<>(contracts.values()), violations);
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      Matcher update = UPDATE.matcher(evidence(task));
      while (update.find()) {
        TableContract contract = contracts.get(entityKey(update.group(1)));
        if (contract == null || contract.taskId().equals(task.id())) continue;
        LinkedHashSet<String> assigned = new LinkedHashSet<>();
        Matcher assignment = ASSIGNMENT.matcher(update.group(2));
        while (assignment.find()) assigned.add(assignment.group(1).toLowerCase(Locale.ROOT));
        assigned.removeAll(contract.fields());
        if (assigned.isEmpty()) continue;
        violations.add(
            task.id()
                + ": SQL UPDATE of "
                + update.group(1).toLowerCase(Locale.ROOT)
                + " assigns undeclared fields "
                + assigned
                + "; canonical table contract in "
                + contract.taskId()
                + " declares "
                + contract.fields());
      }
    }
  }

  private static Map<String, TableContract> explicitTableContracts(List<PlannedTask> tasks) {
    Map<String, TableContract> contracts = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      Matcher declaration = TABLE_DECLARATION.matcher(evidence(task));
      while (declaration.find()) {
        LinkedHashSet<String> fields = declarationFields(declaration.group(2));
        if (fields.size() < 3) continue;
        String entity = entityKey(declaration.group(1));
        TableContract previous = contracts.get(entity);
        if (previous == null || previous.taskId().equals(task.id())) {
          contracts.put(
              entity, new TableContract(task.id(), declaration.group(1), List.copyOf(fields)));
        }
      }
      Matcher markdownDeclaration = MARKDOWN_MODEL_DECLARATION.matcher(evidence(task));
      while (markdownDeclaration.find()) {
        LinkedHashSet<String> fields = backtickDeclarationFields(markdownDeclaration.group(2));
        if (fields.size() < 3) continue;
        registerContract(contracts, task, markdownDeclaration.group(1), List.copyOf(fields));
      }
    }
    return contracts;
  }

  private static void registerContract(
      Map<String, TableContract> contracts,
      PlannedTask task,
      String entityName,
      List<String> fields) {
    String entity = entityKey(entityName);
    TableContract previous = contracts.get(entity);
    if (previous == null || previous.taskId().equals(task.id())) {
      contracts.put(entity, new TableContract(task.id(), entityName, fields));
    }
  }

  private static LinkedHashSet<String> backtickDeclarationFields(String value) {
    LinkedHashSet<String> fields = new LinkedHashSet<>();
    Matcher field = Pattern.compile("`([a-z_][a-z0-9_]*)`").matcher(safe(value));
    while (field.find()) {
      String candidate = field.group(1).toLowerCase(Locale.ROOT);
      if (!NON_FIELDS.contains(candidate)) fields.add(candidate);
    }
    return fields;
  }

  private static void addNearDuplicateAliasViolations(
      List<TableContract> contracts, List<String> violations) {
    for (int left = 0; left < contracts.size(); left++) {
      for (int right = left + 1; right < contracts.size(); right++) {
        TableContract first = contracts.get(left);
        TableContract second = contracts.get(right);
        if (first.taskId().equals(second.taskId())) continue;
        LinkedHashSet<String> entityOverlap = semanticEntityTokens(first.entityName());
        entityOverlap.retainAll(semanticEntityTokens(second.entityName()));
        if (entityOverlap.isEmpty()) continue;
        LinkedHashSet<String> fieldOverlap = new LinkedHashSet<>(first.fields());
        fieldOverlap.retainAll(second.fields());
        int smaller = Math.min(first.fields().size(), second.fields().size());
        if (fieldOverlap.size() < 3 || fieldOverlap.size() * 4 < smaller * 3) continue;
        violations.add(
            second.taskId()
                + ": persistence contracts "
                + first.entityName()
                + " and "
                + second.entityName()
                + " are near-duplicate aliases across tasks "
                + first.taskId()
                + " and "
                + second.taskId()
                + " with shared fields "
                + fieldOverlap
                + "; choose one canonical aggregate or declare distinct table ownership");
      }
    }
  }

  private static LinkedHashSet<String> semanticEntityTokens(String value) {
    String snake =
        safe(value)
            .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .replace('-', '_')
            .toLowerCase(Locale.ROOT);
    LinkedHashSet<String> tokens = new LinkedHashSet<>(java.util.Arrays.asList(snake.split("_+")));
    tokens.removeIf(token -> token.isBlank() || GENERIC_ENTITY_TOKENS.contains(token));
    return tokens;
  }

  private static LinkedHashSet<String> declarationFields(String value) {
    LinkedHashSet<String> fields = new LinkedHashSet<>();
    for (String raw : safe(value).split(",")) {
      Matcher identifier = FIELD.matcher(raw.strip());
      if (!identifier.find()) continue;
      String field = identifier.group(1).toLowerCase(Locale.ROOT);
      if (!NON_FIELDS.contains(field)) fields.add(field);
    }
    return fields;
  }

  private static String entityKey(String value) {
    String key =
        safe(value)
            .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .replace('-', '_')
            .toLowerCase(Locale.ROOT);
    if (key.endsWith("ies") && key.length() > 3) return key.substring(0, key.length() - 3) + "y";
    if (key.endsWith("s") && key.length() > 3) return key.substring(0, key.length() - 1);
    return key;
  }

  private static String evidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  private record TableContract(String taskId, String entityName, List<String> fields) {}
}
