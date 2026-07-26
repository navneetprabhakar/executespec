package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects entity-scoped UUID/number identifier contradictions across backend and frontend models.
 */
final class PlanCrossStackIdentifierTypeGate {
  private static final Pattern TS_SHAPE =
      Pattern.compile(
          "(?is)\\b(?:export\\s+)?(?:interface\\s+|type\\s+)([A-Z][A-Za-z0-9_]*)"
              + "\\s*(?:=\\s*)?\\{([^}]*)}");
  private static final Pattern PYTHON_CLASS =
      Pattern.compile(
          "(?m)^\\s*class\\s+([A-Z][A-Za-z0-9_]*)\\s*(?:\\([^\\n)]*\\))?\\s*:\\s*\\n"
              + "((?:[ \\t]+[^\\n]*(?:\\n|$))+)");
  private static final Pattern JAVA_RECORD =
      Pattern.compile("(?is)\\brecord\\s+([A-Z][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");
  private static final Pattern JAVA_CLASS =
      Pattern.compile("(?is)\\bclass\\s+([A-Z][A-Za-z0-9_]*)[^\\{]*\\{([^}]*)}");
  private static final Pattern INLINE_SHAPE =
      Pattern.compile(
          "(?is)\\b([A-Z][A-Za-z0-9_]*(?:Response|DTO|Dto|Out|View|Model))\\s*\\(([^)]*)\\)");
  private static final Pattern COLON_FIELD =
      Pattern.compile(
          "(?i)\\b([a-z_][a-zA-Z0-9_]*)\\s*:\\s*" + "(UUID|number|int|integer|long|string|str)\\b");
  private static final Pattern JAVA_FIELD =
      Pattern.compile("(?i)\\b(UUID|Integer|Long|String|int|long)\\s+([a-z_][a-zA-Z0-9_]*)\\b");
  private static final Pattern INTERFACE_LIST =
      Pattern.compile(
          "(?is)\\b(?:define|create|add|implement)\\b.{0,100}?"
              + "\\b(?:typescript\\s+)?interfaces?\\b(.{0,600}?)(?:\\.|\\n|\\buse\\b)");
  private static final Pattern BACKTICK_NAME = Pattern.compile("`([A-Z][A-Za-z0-9_]*)`");
  private static final Pattern NUMERIC_ID_EXAMPLE = Pattern.compile("(?i)\\bid\\s*:\\s*number\\b");

  private PlanCrossStackIdentifierTypeGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    List<Declaration> declarations = declarations(plan);
    for (Declaration backend : declarations) {
      if (backend.stack() != Stack.BACKEND || backend.type() != IdentifierType.UUID) continue;
      declarations.stream()
          .filter(frontend -> frontend.stack() == Stack.FRONTEND)
          .filter(frontend -> frontend.type() == IdentifierType.NUMBER)
          .filter(frontend -> frontend.entity().equals(backend.entity()))
          .filter(frontend -> frontend.field().equals(backend.field()))
          .forEach(
              frontend ->
                  violations.add(
                      frontend.taskId()
                          + ": frontend "
                          + frontend.declaredEntity()
                          + "."
                          + frontend.field()
                          + " uses numeric identifier type but backend "
                          + backend.taskId()
                          + " declares UUID in "
                          + backend.declaredEntity()
                          + "."
                          + backend.field()));
    }
  }

  private static List<Declaration> declarations(Plan plan) {
    List<Declaration> declarations = new ArrayList<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      String evidence = evidence(task);
      collectShapes(task.id(), evidence, TS_SHAPE, Stack.FRONTEND, declarations);
      collectShapes(task.id(), evidence, PYTHON_CLASS, Stack.BACKEND, declarations);
      collectShapes(task.id(), evidence, JAVA_RECORD, Stack.BACKEND, declarations);
      collectShapes(task.id(), evidence, JAVA_CLASS, Stack.BACKEND, declarations);
      collectShapes(task.id(), evidence, INLINE_SHAPE, Stack.BACKEND, declarations);
      collectInterfaceListExamples(task.id(), evidence, declarations);
    }
    return List.copyOf(new LinkedHashSet<>(declarations));
  }

  private static void collectShapes(
      String taskId,
      String evidence,
      Pattern shapePattern,
      Stack stack,
      List<Declaration> declarations) {
    Matcher shape = shapePattern.matcher(evidence);
    while (shape.find()) {
      String entity = shape.group(1);
      String body = shape.group(2);
      Matcher fields =
          (stack == Stack.FRONTEND ? COLON_FIELD : backendFieldPattern(body)).matcher(body);
      while (fields.find()) {
        String field = stack == Stack.FRONTEND ? fields.group(1) : backendField(fields, body);
        String rawType = stack == Stack.FRONTEND ? fields.group(2) : backendType(fields, body);
        addDeclaration(taskId, entity, field, rawType, stack, declarations);
      }
    }
  }

  private static Pattern backendFieldPattern(String body) {
    return COLON_FIELD.matcher(body).find() ? COLON_FIELD : JAVA_FIELD;
  }

  private static String backendField(Matcher matcher, String body) {
    return COLON_FIELD.matcher(body).find() ? matcher.group(1) : matcher.group(2);
  }

  private static String backendType(Matcher matcher, String body) {
    return COLON_FIELD.matcher(body).find() ? matcher.group(2) : matcher.group(1);
  }

  private static void collectInterfaceListExamples(
      String taskId, String evidence, List<Declaration> declarations) {
    if (!NUMERIC_ID_EXAMPLE.matcher(evidence).find()) return;
    Matcher list = INTERFACE_LIST.matcher(evidence);
    while (list.find()) {
      Matcher name = BACKTICK_NAME.matcher(list.group(1));
      while (name.find()) {
        addDeclaration(taskId, name.group(1), "id", "number", Stack.FRONTEND, declarations);
      }
    }
  }

  private static void addDeclaration(
      String taskId,
      String declaredEntity,
      String rawField,
      String rawType,
      Stack stack,
      List<Declaration> declarations) {
    String field = canonicalField(rawField);
    if (field == null) return;
    IdentifierType type = IdentifierType.from(rawType);
    if (type == IdentifierType.OTHER) return;
    declarations.add(
        new Declaration(
            taskId, declaredEntity, canonicalEntity(declaredEntity), field, type, stack));
  }

  private static String canonicalEntity(String value) {
    return safe(value)
        .replaceFirst("(?i)(?:Response|DTO|Dto|Out|View|Entity|Model|Interface)$", "")
        .toLowerCase(Locale.ROOT);
  }

  private static String canonicalField(String value) {
    String field = safe(value).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    return field.equals("id") || field.endsWith("_id") ? field : null;
  }

  private static String evidence(PlannedTask task) {
    return safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  private enum Stack {
    BACKEND,
    FRONTEND
  }

  private enum IdentifierType {
    UUID,
    NUMBER,
    STRING,
    OTHER;

    private static IdentifierType from(String raw) {
      return switch (safe(raw).toLowerCase(Locale.ROOT)) {
        case "uuid" -> UUID;
        case "number", "int", "integer", "long" -> NUMBER;
        case "string", "str" -> STRING;
        default -> OTHER;
      };
    }
  }

  private record Declaration(
      String taskId,
      String declaredEntity,
      String entity,
      String field,
      IdentifierType type,
      Stack stack) {}
}
