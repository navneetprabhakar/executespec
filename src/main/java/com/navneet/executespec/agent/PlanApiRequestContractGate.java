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

/** Compares explicit producer request schemas with literal frontend/test request bodies. */
final class PlanApiRequestContractGate {
  private static final Pattern NAMED_REQUEST_SCHEMA =
      Pattern.compile(
          "(?im)(?:^|[-*]\\s+)(?:`|\\*\\*)?([A-Z][A-Za-z0-9]*(?:Create|Request|Input))"
              + "(?:`|\\*\\*)?\\s*(?::|—|-)\\s*([^\\n]+)");
  private static final Pattern FIELD =
      Pattern.compile("(?i)(?:`|\")?([a-z_][a-z0-9_]*)(?:`|\")?\\s*(?=[:(])");
  private static final Pattern TYPE_REFERENCE =
      Pattern.compile("\\b([A-Z][A-Za-z0-9]*(?:Create|Request|Input))\\b");
  private static final Pattern REQUEST_BODY =
      Pattern.compile("(?is)request\\s+body\\s*:\\s*`?(\\{[^\\n]+})`?");
  private static final Pattern BODY_ENDPOINT =
      Pattern.compile("(?i)\\b(POST|PUT)\\s*/([a-z0-9_{}?=&./:-]+)");

  private PlanApiRequestContractGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return;
    Map<String, Map<String, RequestSchema>> schemasByTask = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null || !isBackendTask(task)) continue;
      Map<String, RequestSchema> schemas = requestSchemas(taskEvidence(task));
      if (!schemas.isEmpty()) schemasByTask.put(task.id(), schemas);
    }
    if (schemasByTask.isEmpty()) return;

    List<PlanApiContractEvidence.ApiEvidence> endpoints =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(task -> PlanApiContractEvidence.extract(task).stream())
            .filter(endpoint -> endpoint.method().equals("POST") || endpoint.method().equals("PUT"))
            .toList();
    for (PlannedTask consumerTask : plan.tasks()) {
      if (consumerTask == null || isBackendTask(consumerTask)) continue;
      String evidence = taskEvidence(consumerTask);
      List<RequestBody> bodies = requestBodies(evidence);
      if (bodies.isEmpty()) continue;
      List<PlanApiContractEvidence.ApiEvidence> consumerEndpoints =
          endpoints.stream()
              .filter(endpoint -> endpoint.taskId().equals(consumerTask.id()))
              .toList();
      for (RequestBody body : bodies) {
        PlanApiContractEvidence.ApiEvidence consumer =
            consumerEndpoints.stream()
                .filter(endpoint -> endpoint.method().equals(body.method()))
                .filter(endpoint -> endpoint.path().equals(body.path()))
                .findFirst()
                .orElse(null);
        if (consumer == null) continue;
        List<PlanApiContractEvidence.ApiEvidence> producers =
            endpoints.stream()
                .filter(endpoint -> !endpoint.taskId().equals(consumerTask.id()))
                .filter(endpoint -> endpoint.method().equals(consumer.method()))
                .filter(endpoint -> endpoint.path().equals(consumer.path()))
                .filter(endpoint -> schemasByTask.containsKey(endpoint.taskId()))
                .toList();
        if (producers.size() != 1) continue;
        PlanApiContractEvidence.ApiEvidence producer = producers.getFirst();
        Set<String> expected =
            expectedFields(producer.path(), schemasByTask.get(producer.taskId()));
        if (expected.isEmpty()) continue;
        List<String> missing =
            expected.stream().filter(field -> !body.fields().contains(field)).sorted().toList();
        if (!missing.isEmpty()) {
          violations.add(
              consumerTask.id()
                  + ": request body for "
                  + consumer.method()
                  + " "
                  + PlanApiContractEvidence.displayPath(consumer.path())
                  + " omits producer fields "
                  + missing
                  + " declared by "
                  + producer.taskId());
        }
      }
    }
  }

  private static Set<String> expectedFields(
      String normalizedPath, Map<String, RequestSchema> schemas) {
    String resource = normalizedPath;
    int slash = resource.indexOf('/', 1);
    if (slash > 0) resource = resource.substring(1, slash);
    else resource = resource.replace("/", "");
    resource = PlanApiContractEvidence.singular(resource);
    String prefix = resource.replaceAll("[^a-z0-9]", "");
    RequestSchema root =
        schemas.values().stream()
            .filter(schema -> schema.name().toLowerCase(Locale.ROOT).endsWith("create"))
            .filter(
                schema ->
                    schema
                        .name()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "")
                        .startsWith(prefix))
            .min(java.util.Comparator.comparingInt(schema -> schema.name().length()))
            .orElse(null);
    if (root == null) return Set.of();
    LinkedHashSet<String> fields = new LinkedHashSet<>(root.fields());
    for (String reference : root.references()) {
      RequestSchema nested = schemas.get(reference);
      if (nested != null) fields.addAll(nested.fields());
    }
    return Set.copyOf(fields);
  }

  private static Map<String, RequestSchema> requestSchemas(String evidence) {
    Map<String, RequestSchema> schemas = new LinkedHashMap<>();
    Matcher matcher = NAMED_REQUEST_SCHEMA.matcher(evidence);
    while (matcher.find()) {
      LinkedHashSet<String> fields = new LinkedHashSet<>();
      Matcher field = FIELD.matcher(matcher.group(2));
      while (field.find()) fields.add(field.group(1).toLowerCase(Locale.ROOT));
      if (fields.isEmpty()) continue;
      LinkedHashSet<String> references = new LinkedHashSet<>();
      Matcher reference = TYPE_REFERENCE.matcher(matcher.group(2));
      while (reference.find()) {
        if (!reference.group(1).equals(matcher.group(1))) references.add(reference.group(1));
      }
      schemas.put(
          matcher.group(1),
          new RequestSchema(matcher.group(1), Set.copyOf(fields), Set.copyOf(references)));
    }
    return Map.copyOf(schemas);
  }

  private static List<RequestBody> requestBodies(String evidence) {
    List<RequestBody> bodies = new ArrayList<>();
    Matcher matcher = REQUEST_BODY.matcher(evidence);
    while (matcher.find()) {
      int contextStart = Math.max(0, matcher.start() - 600);
      Matcher endpoint = BODY_ENDPOINT.matcher(evidence.substring(contextStart, matcher.start()));
      String method = null;
      String path = null;
      while (endpoint.find()) {
        method = endpoint.group(1).toUpperCase(Locale.ROOT);
        path =
            PlanApiContractEvidence.normalizedPath(
                "/" + endpoint.group(2).replaceAll("[`'\".,;:]+$", ""));
      }
      if (method == null) continue;
      LinkedHashSet<String> fields = new LinkedHashSet<>();
      Matcher field = FIELD.matcher(matcher.group(1));
      while (field.find()) fields.add(field.group(1).toLowerCase(Locale.ROOT));
      if (!fields.isEmpty()) bodies.add(new RequestBody(method, path, Set.copyOf(fields)));
    }
    return List.copyOf(bodies);
  }

  private static boolean isBackendTask(PlannedTask task) {
    return task.touchesFiles() != null
        && task.touchesFiles().stream()
            .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
            .anyMatch(path -> path.startsWith("backend/") || path.contains("/backend/"));
  }

  private static String taskEvidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  private record RequestSchema(String name, Set<String> fields, Set<String> references) {}

  private record RequestBody(String method, String path, Set<String> fields) {}
}
