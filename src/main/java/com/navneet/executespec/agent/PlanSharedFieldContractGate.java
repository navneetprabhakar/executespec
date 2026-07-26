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
  private static final Pattern MARKDOWN_DECLARATION =
      Pattern.compile(
          "(?i)^\\s*[-*]\\s+(?:\\*\\*|`)?([A-Z][A-Za-z0-9_]+)(?:\\*\\*|`)?" + "\\s*[—–-]\\s*(.*)$");
  private static final Pattern FIELD =
      Pattern.compile(
          "(?i)(?:^|[,;]\\s*)`?\\s*[\\[{]?\\s*([a-z_][a-z0-9_]*)(?=\\s*(?:\\(|:|[,;]|[\\]}]|`|$))");
  private static final Pattern TYPED_FIELD =
      Pattern.compile(
          "(?i)(?:^|[,;]\\s*)`?\\s*[\\[{]?\\s*([a-z_][a-z0-9_]*)`?\\s*"
              + "(?:\\(\\s*([a-z][a-z0-9_<>\\[\\]|?]*)|:\\s*([a-z][a-z0-9_<>\\[\\]|?]*))");
  private static final Pattern BULLET_FIELD =
      Pattern.compile("(?i)^\\s*[-*]\\s+`?([a-z_][a-z0-9_]*)`?\\s*(?=\\(|:|[-–—]|$)");
  private static final Pattern INLINE_DECLARATION =
      Pattern.compile(
          "(?im)(?:^|[-*]\\s+|\\bthe\\s+)(?:\\*\\*|`)?([A-Z][A-Za-z0-9_]+)"
              + "(?:\\*\\*|`)?(?:\\s+fields)?\\s*:\\s*([^\\n.]+)");
  private static final Pattern EXPLICIT_SHARED_PROPS =
      Pattern.compile(
          "(?is)(?:\\b(?:shared|common|exported|reusable)\\b.{0,40}\\bprops\\b"
              + "|\\b(?:reuse|import|consume)\\b.{0,40}\\bprops\\b)");

  private PlanSharedFieldContractGate() {}

  static void addViolations(List<PlannedTask> tasks, List<String> violations) {
    Map<String, Map<String, Declaration>> byContract = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      extract(task)
          .forEach(
              (contract, declaration) ->
                  byContract
                      .computeIfAbsent(contract, ignored -> new LinkedHashMap<>())
                      .computeIfAbsent(task.id(), ignored -> new Declaration())
                      .merge(declaration));
    }
    byContract.forEach((contract, declarations) -> compare(contract, declarations, violations));
  }

  private static Map<String, Declaration> extract(PlannedTask task) {
    String[] lines = evidence(task).split("\\R", -1);
    Map<String, Declaration> declarations = new LinkedHashMap<>();
    for (int index = 0; index < lines.length; index++) {
      Matcher header = DECLARATION.matcher(lines[index]);
      if (!header.matches()) header = MARKDOWN_DECLARATION.matcher(lines[index]);
      if (!header.matches()) continue;
      Declaration declaration = declaration(header.group(2));
      int following = index + 1;
      while (following < lines.length) {
        Matcher bullet = BULLET_FIELD.matcher(lines[following]);
        if (!bullet.find()) break;
        declaration.fields().add(bullet.group(1).toLowerCase(Locale.ROOT));
        declaration.mergeTypes(fieldTypes(lines[following]));
        following++;
      }
      if (declaration.fields().size() < 3) continue;
      String contract = header.group(1).toLowerCase(Locale.ROOT);
      if (isTaskLocalProps(contract, task)) continue;
      declarations.computeIfAbsent(contract, ignored -> new Declaration()).merge(declaration);
      index = Math.max(index, following - 1);
    }
    Matcher inline = INLINE_DECLARATION.matcher(evidence(task));
    while (inline.find()) {
      Declaration declaration = declaration(inline.group(2));
      if (declaration.fields().size() < 3) continue;
      String contract = inline.group(1).toLowerCase(Locale.ROOT);
      if (isTaskLocalProps(contract, task)) continue;
      declarations.computeIfAbsent(contract, ignored -> new Declaration()).merge(declaration);
    }
    return declarations;
  }

  /**
   * A bare {@code Props} declaration is conventionally scoped to its component, not a globally
   * named data contract. Comparing every such declaration across unrelated UI tasks conflates
   * component inputs solely because both authors used the conventional local name. A plan can still
   * ask this gate to compare a genuinely shared Props type by saying that it is shared, common,
   * exported, reusable, imported, reused, or consumed.
   */
  private static boolean isTaskLocalProps(String contract, PlannedTask task) {
    return "props".equals(contract) && !EXPLICIT_SHARED_PROPS.matcher(evidence(task)).find();
  }

  private static Set<String> fields(String value) {
    LinkedHashSet<String> fields = new LinkedHashSet<>();
    Matcher matcher = FIELD.matcher(safe(value));
    while (matcher.find()) fields.add(matcher.group(1).toLowerCase(Locale.ROOT));
    return fields;
  }

  private static Declaration declaration(String value) {
    return new Declaration(fields(value), fieldTypes(value));
  }

  private static Map<String, String> fieldTypes(String value) {
    LinkedHashMap<String, String> types = new LinkedHashMap<>();
    Matcher matcher = TYPED_FIELD.matcher(safe(value));
    while (matcher.find()) {
      String rawType = matcher.group(2) == null ? matcher.group(3) : matcher.group(2);
      types.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), normalizedType(rawType));
    }
    return types;
  }

  private static String normalizedType(String value) {
    String normalized = safe(value).toLowerCase(Locale.ROOT).replace("?", "");
    if (Set.of("int", "integer", "long", "float", "double", "number", "decimal")
        .contains(normalized)) return "number";
    if (Set.of("str", "string", "text", "varchar", "uuid", "datetime", "date", "timestamp")
        .contains(normalized)) return "string";
    if (Set.of("bool", "boolean").contains(normalized)) return "boolean";
    return normalized;
  }

  private static void compare(
      String contract, Map<String, Declaration> declarations, List<String> violations) {
    List<Map.Entry<String, Declaration>> entries = new ArrayList<>(declarations.entrySet());
    for (int left = 0; left < entries.size(); left++) {
      for (int right = left + 1; right < entries.size(); right++) {
        Map.Entry<String, Declaration> first = entries.get(left);
        Map.Entry<String, Declaration> second = entries.get(right);
        Set<String> shared = new LinkedHashSet<>(first.getValue().fields());
        shared.retainAll(second.getValue().fields());
        if (shared.isEmpty()) continue;
        if (!first.getValue().fields().equals(second.getValue().fields())) {
          Set<String> firstOnly = difference(first.getValue().fields(), second.getValue().fields());
          Set<String> secondOnly =
              difference(second.getValue().fields(), first.getValue().fields());
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
          continue;
        }
        LinkedHashMap<String, String> mismatchedTypes = new LinkedHashMap<>();
        for (String field : shared) {
          String firstType = first.getValue().types().get(field);
          String secondType = second.getValue().types().get(field);
          if (firstType != null && secondType != null && !firstType.equals(secondType)) {
            mismatchedTypes.put(field, firstType + " vs " + secondType);
          }
        }
        if (!mismatchedTypes.isEmpty()) {
          violations.add(
              second.getKey()
                  + ": shared "
                  + contract
                  + " field type contract differs from "
                  + first.getKey()
                  + "; mismatched="
                  + mismatchedTypes);
        }
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

  private static final class Declaration {
    private final LinkedHashSet<String> fields;
    private final LinkedHashMap<String, String> types;

    private Declaration() {
      this(Set.of(), Map.of());
    }

    private Declaration(Set<String> fields, Map<String, String> types) {
      this.fields = new LinkedHashSet<>(fields);
      this.types = new LinkedHashMap<>(types);
    }

    private LinkedHashSet<String> fields() {
      return fields;
    }

    private Map<String, String> types() {
      return types;
    }

    private void merge(Declaration other) {
      fields.addAll(other.fields);
      mergeTypes(other.types);
    }

    private void mergeTypes(Map<String, String> other) {
      other.forEach(types::putIfAbsent);
    }
  }
}
