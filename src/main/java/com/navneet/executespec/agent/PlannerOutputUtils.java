package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.LlmProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared parsing + validation helpers for the V2 planner pipeline. {@link OutlinePlannerAgent},
 * validation tests, and downstream plan repair code all use this as the single source of truth for
 * planner-output handling.
 *
 * <p>All methods are stateless; the only injected dependency is the {@link ObjectMapper} used for
 * JSON parsing, passed per-call so callers keep their own Spring-managed mapper.
 */
public final class PlannerOutputUtils {

  private static final Logger log = LoggerFactory.getLogger(PlannerOutputUtils.class);

  private PlannerOutputUtils() {}

  // Plan parsing

  /**
   * Parse a {@code submitPlan}/{@code submitOutline} tool-call argument JSON string into a {@link
   * Plan}. Robust to malformed Anthropic tool-call payloads (the {@code <parameter name="..."} XML
   * salvage path) and to skeleton-mode plans where descriptions / hardConstraints are intentionally
   * blank.
   */
  public static Plan parsePlanFromArgs(
      String argsJson, Classification classification, ObjectMapper objectMapper) {
    return parsePlanFromArgs(argsJson, classification, null, objectMapper);
  }

  public static Plan parsePlanFromArgs(
      String argsJson,
      Classification classification,
      PlanStrategy planStrategy,
      ObjectMapper objectMapper) {
    JsonNode root;
    try {
      root = objectMapper.readTree(argsJson);
    } catch (Exception e) {
      throw new IllegalStateException(
          "submitPlan/submitOutline args are not valid JSON: "
              + e.getMessage()
              + "; args="
              + truncate(argsJson, 400),
          e);
    }

    List<PlannedTask> tasks = new ArrayList<>();
    JsonNode tasksNode = root.path("tasks");
    int maxTasks = planStrategy == null ? classification.maxTasks() : planStrategy.maxTasks();
    String capLabel =
        planStrategy == null ? "classification=" + classification : "planStrategy=" + planStrategy;

    // Salvage path: Anthropic occasionally emits its internal
    // <parameter name="..."> XML inline instead of a clean tool-call payload,
    // shoving the tasks array INSIDE the smokeTestStrategy string value.
    // Recover so the run doesn't fail outright.
    if (!tasksNode.isArray()) {
      String leakedBlob = root.path("smokeTestStrategy").asText("");
      JsonNode salvaged = salvageTasksFromBlob(leakedBlob, objectMapper);
      if (salvaged != null) {
        log.warn(
            "PlannerOutputUtils: recovered tasks array from malformed smokeTestStrategy blob "
                + "({} tasks). Anthropic model went off-protocol.",
            salvaged.size());
        tasksNode = salvaged;
      }
    }

    if (tasksNode.isArray()) {
      int idx = 0;
      for (JsonNode t : tasksNode) {
        if (idx >= maxTasks) {
          log.warn(
              "PlannerOutputUtils: planner exceeded maxTasks={} for {}, truncating",
              maxTasks,
              capLabel);
          break;
        }
        tasks.add(buildTask(t, idx + 1));
        idx++;
      }
    }

    String smokeStrategyStr = root.path("smokeTestStrategy").asText("BUILD");
    Plan.SmokeTestStrategy smokeStrategy;
    try {
      smokeStrategy = Plan.SmokeTestStrategy.valueOf(smokeStrategyStr.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException iae) {
      log.warn("Unknown smokeTestStrategy '{}', falling back to BUILD", smokeStrategyStr);
      smokeStrategy = Plan.SmokeTestStrategy.BUILD;
    }

    Plan.RuntimeSurface runtimeSurface = Plan.RuntimeSurface.HTTP;
    String surfaceStr = root.path("runtimeSurface").asText("HTTP");
    try {
      runtimeSurface =
          Plan.RuntimeSurface.valueOf(surfaceStr.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException ignored) {
      log.warn("Unknown runtimeSurface '{}', falling back to HTTP", surfaceStr);
    }

    List<RequirementSpec> requirements = parseRequirements(root.path("requirements"));

    return Plan.builder()
        .planTitle(root.path("planTitle").asText(null))
        .planSummary(root.path("planSummary").asText(null))
        .projectType(root.path("projectType").asText("unknown"))
        .buildCommand(
            root.path("buildCommand").isNull() ? null : root.path("buildCommand").asText(null))
        .smokeTestStrategy(smokeStrategy)
        .runtimeSurface(runtimeSurface)
        .tasks(tasks)
        .classification(classification)
        .requirements(requirements)
        .build();
  }

  private static List<RequirementSpec> parseRequirements(JsonNode arr) {
    if (arr == null || !arr.isArray()) return List.of();
    List<RequirementSpec> out = new ArrayList<>();
    for (JsonNode n : arr) {
      String id = n.path("id").asText("").trim();
      String text = n.path("text").asText("").trim();
      if (id.isEmpty() || text.isEmpty()) continue;
      RequirementSpec.RequirementKind kind;
      try {
        String kStr = n.path("kind").asText("FUNCTIONAL").trim().toUpperCase(Locale.ROOT);
        kind = RequirementSpec.RequirementKind.valueOf(kStr);
      } catch (IllegalArgumentException e) {
        kind = RequirementSpec.RequirementKind.FUNCTIONAL;
      }
      out.add(new RequirementSpec(id, text, kind));
    }
    return out;
  }

  private static JsonNode salvageTasksFromBlob(String blob, ObjectMapper objectMapper) {
    if (blob == null || blob.isEmpty()) return null;
    int marker = blob.indexOf("name=\"tasks\"");
    if (marker < 0) return null;
    int start = blob.indexOf('[', marker);
    if (start < 0) return null;
    int depth = 0;
    int end = -1;
    for (int i = start; i < blob.length(); i++) {
      char c = blob.charAt(i);
      if (c == '[') depth++;
      else if (c == ']') {
        depth--;
        if (depth == 0) {
          end = i;
          break;
        }
      }
    }
    if (end < 0) return null;
    String arrayJson = blob.substring(start, end + 1);
    try {
      JsonNode node = objectMapper.readTree(arrayJson);
      return node.isArray() ? node : null;
    } catch (Exception e) {
      log.warn("PlannerOutputUtils: salvage parse failed: {}", e.getMessage());
      return null;
    }
  }

  private static PlannedTask buildTask(JsonNode t, int seq) {
    String id = t.path("id").asText(null);
    if (id == null || id.isBlank()) id = "T" + seq;
    PlannedTask.Complexity cx;
    try {
      cx =
          PlannedTask.Complexity.valueOf(
              t.path("complexity").asText("MEDIUM").toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException iae) {
      cx = PlannedTask.Complexity.MEDIUM;
    }
    PlannedTask.TaskType taskType;
    try {
      taskType =
          PlannedTask.TaskType.valueOf(t.path("type").asText("LEAF").toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException iae) {
      taskType = PlannedTask.TaskType.LEAF;
    }
    String description = t.path("description").asText("");
    if (description.isBlank() && taskType == PlannedTask.TaskType.LEAF) {
      description = "(no description)";
    }
    return PlannedTask.builder()
        .id(id)
        .title(t.path("title").asText("(untitled)"))
        .description(description)
        .complexity(cx)
        .touchesFiles(stringList(t.path("touchesFiles")))
        .dependsOn(stringList(t.path("dependsOn")))
        .hardConstraints(stringList(t.path("hardConstraints")))
        .meetsRequirements(stringList(t.path("meetsRequirements")))
        .taskType(taskType)
        .build();
  }

  private static List<String> stringList(JsonNode arr) {
    List<String> out = new ArrayList<>();
    if (arr != null && arr.isArray()) {
      arr.forEach(
          n -> {
            if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText());
          });
    }
    return out;
  }

  // Validation v3 / Pillar 1 — coverage rules

  /**
   * Validate the plan's requirement graph. Returns a list of violations — empty when the plan is
   * clean. Hierarchical skeletons use {@link #validateRequirementCoverage(Plan)} before expansion;
   * this method adds body/file/test-quality rules that require a fully expanded plan.
   */
  public static List<String> validatePlan(Plan plan) {
    List<String> out = new ArrayList<>(validateRequirementCoverage(plan));
    if (plan == null || plan.requirements() == null || plan.requirements().isEmpty()) {
      return out;
    }
    List<RequirementSpec> reqs = plan.requirements();
    List<PlannedTask> tasks = plan.tasks() == null ? List.of() : plan.tasks();

    String requirementText =
        reqs.stream()
            .map(RequirementSpec::text)
            .filter(java.util.Objects::nonNull)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.joining("\n"));
    if (!explicitlyRequestsStandaloneApiSpecification(requirementText)) {
      Set<String> inventedOpenApi = new LinkedHashSet<>();
      for (PlannedTask task : tasks) {
        boolean ownsOpenApiFile =
            task.touchesFiles() != null
                && task.touchesFiles().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(value -> value.replace('\\', '/').toLowerCase(Locale.ROOT))
                    .anyMatch(
                        value ->
                            value.endsWith("/openapi.yaml")
                                || value.endsWith("/openapi.yml")
                                || value.equals("openapi.yaml")
                                || value.equals("openapi.yml")
                                || value.endsWith("/swagger.json")
                                || value.equals("swagger.json"));
        if (ownsOpenApiFile) inventedOpenApi.add(task.id());
      }
      if (!inventedOpenApi.isEmpty()) {
        out.add(
            "Unrequested OpenAPI/Swagger deliverable in task(s) "
                + inventedOpenApi
                + "; framework-generated runtime docs are not authorization to create a"
                + " standalone specification file.");
      }
    }
    out.addAll(PlanTaskOwnershipValidator.validate(tasks));

    Plan.RuntimeSurface surface = plan.runtimeSurface();
    // RR-1220 — STATIC_WEB was blanket-exempt from the strict test gate even though the planner
    // prompts require production-code tests for functional React/static-JS apps (only a genuinely
    // untestable single-file page is exempt, which is what the isSingleShot bypass already covers).
    // Include it so a functional static-web plan cannot pass strict planning with zero tests.
    boolean testableSurface =
        surface == Plan.RuntimeSurface.HTTP
            || surface == Plan.RuntimeSurface.CLI
            || surface == Plan.RuntimeSurface.LIBRARY
            || surface == Plan.RuntimeSurface.STATIC_WEB
            || surface == Plan.RuntimeSurface.MOBILE_APP;
    boolean isSingleShot = plan.classification() == Classification.SINGLE_SHOT;
    if (testableSurface && !isSingleShot) {
      boolean hasTestRequirement =
          reqs.stream().anyMatch(r -> r.kind() == RequirementSpec.RequirementKind.TEST);
      // RR-1219 — the previous check accepted any task whose title/description merely CONTAINED the
      // substring "test", so "attestation support", "tests out of scope" and "Do not add tests" all
      // satisfied the mandatory test gate. Require a genuine, non-negated test signal instead.
      boolean hasTestTask =
          tasks.stream().anyMatch(t -> looksLikeGenuineTestTask(t.title(), t.description()));
      if (!hasTestRequirement && !hasTestTask) {
        out.add(
            "No test task or TEST-kind requirement on a testable surface ("
                + surface
                + ") — strict v3 requires at least one.");
      }
    }
    return out;
  }

  /**
   * Validate only the compact requirement-allocation graph shared by full plans and hierarchical
   * skeletons. This deliberately avoids description, file-ownership, and test-surface heuristics:
   * those require expanded task bodies. Running this before expansion prevents N parallel expanders
   * from each receiving the whole requirement list and independently guessing ownership.
   */
  public static List<String> validateRequirementCoverage(Plan plan) {
    List<String> out = new ArrayList<>();
    if (plan == null || plan.requirements() == null || plan.requirements().isEmpty()) {
      out.add("No requirements enumerated (top-level 'requirements' array is empty).");
      return out;
    }

    List<RequirementSpec> reqs = plan.requirements();
    Set<String> ids = new HashSet<>();
    Set<String> duplicates = new LinkedHashSet<>();
    for (RequirementSpec requirement : reqs) {
      if (requirement == null || requirement.id() == null || requirement.id().isBlank()) {
        out.add(
            "Requirement has blank id (text="
                + truncate(requirement == null ? null : requirement.text(), 60)
                + ").");
        continue;
      }
      if (!ids.add(requirement.id())) duplicates.add(requirement.id());
    }
    if (!duplicates.isEmpty()) out.add("Duplicate requirement ids: " + duplicates);

    Set<String> covered = new HashSet<>();
    Set<String> unknownRefs = new LinkedHashSet<>();
    Set<String> unownedTasks = new LinkedHashSet<>();
    List<PlannedTask> tasks = plan.tasks() == null ? List.of() : plan.tasks();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      if (task.meetsRequirements() == null || task.meetsRequirements().isEmpty()) {
        unownedTasks.add(task.id() + " (" + truncate(task.title(), 60) + ")");
        continue;
      }
      for (String refId : task.meetsRequirements()) {
        if (!ids.contains(refId)) unknownRefs.add(refId);
        else covered.add(refId);
      }
    }
    if (!unownedTasks.isEmpty()) {
      out.add(
          "Tasks with no requirement ownership (likely invented deliverables): " + unownedTasks);
    }
    if (!unknownRefs.isEmpty()) {
      out.add("Task meetsRequirements references unknown ids: " + unknownRefs);
    }
    Set<String> uncovered = new LinkedHashSet<>(ids);
    uncovered.removeAll(covered);
    if (!uncovered.isEmpty()) {
      out.add("Requirements not covered by any task: " + uncovered);
    }
    return out;
  }

  /**
   * Scope-integrity checks that are safe to enforce independently of the broader validation-v3
   * rollout flag. These reject work the customer did not authorize; they are not advisory quality
   * heuristics.
   */
  public static List<String> validateScopeIntegrity(Plan plan) {
    return validatePlan(plan).stream()
        .filter(
            violation ->
                violation.startsWith("Tasks with no requirement ownership")
                    || violation.startsWith("Unrequested OpenAPI/Swagger deliverable"))
        .toList();
  }

  private static boolean explicitlyRequestsStandaloneApiSpecification(String requirementText) {
    if (requirementText == null || requirementText.isBlank()) return false;
    return containsAny(
        requirementText,
        "openapi.yaml",
        "openapi.yml",
        "swagger.json",
        "deliver openapi",
        "provide openapi",
        "create openapi",
        "generate openapi",
        "export openapi",
        "deliver swagger",
        "provide swagger",
        "create swagger",
        "generate swagger",
        "export swagger",
        "typed frontend api client derived from the backend contract",
        "typed api client derived from the backend contract",
        "openapi-driven frontend api types");
  }

  /**
   * RR-1219 — true when a task's title/description carries a genuine, non-negated test signal.
   * Requires a whole-word {@code test}/{@code tests}/{@code testing}/{@code tested} token (so
   * "attestation" no longer qualifies) AND the absence of a negation phrase that turns the token
   * into a promise NOT to test (so "Do not add tests" and "tests out of scope" no longer qualify).
   */
  static boolean looksLikeGenuineTestTask(String title, String description) {
    String combined =
        ((title == null ? "" : title) + " \n " + (description == null ? "" : description))
            .toLowerCase(Locale.ROOT);
    if (combined.isBlank()) return false;
    // Negation of testing — a task that explicitly declines to test does not satisfy the gate.
    if (combined.contains("do not test")
        || combined.contains("do not add test")
        || combined.contains("don't test")
        || combined.contains("dont test")
        || combined.contains("no test")
        || combined.contains("not test")
        || combined.contains("without test")
        || combined.contains("skip test")
        || combined.contains("avoid test")
        || combined.contains("test out of scope")
        || combined.contains("tests out of scope")
        || combined.contains("no unit test")) {
      return false;
    }
    // Whole-word test token — excludes "attestation", "contest", "latest", "protest", etc.
    return TEST_WORD.matcher(combined).find();
  }

  private static final java.util.regex.Pattern TEST_WORD =
      java.util.regex.Pattern.compile("\\btest(s|ing|ed)?\\b");

  private static boolean containsAny(String text, String... needles) {
    if (text == null || text.isBlank()) return false;
    for (String needle : needles) {
      if (needle != null && !needle.isBlank() && text.contains(needle)) return true;
    }
    return false;
  }

  /**
   * Logs a WARN when the spec carries obvious constraint signals ({@code do NOT}, {@code only},
   * {@code throughout}, etc.) but a majority of tasks have empty {@code hardConstraints}. Non-fatal
   * observability — weaker models occasionally underpropagate intermittently.
   */
  public static void warnIfConstraintsUnderpropagated(Plan plan, String spec) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty() || spec == null) return;
    String specLower = spec.toLowerCase(Locale.ROOT);
    boolean specHasSignals =
        specLower.contains("do not")
            || specLower.contains("don't")
            || specLower.contains(" not ")
            || specLower.contains("only ")
            || specLower.contains(" throughout")
            || specLower.contains("must use")
            || specLower.contains("avoid ");
    if (!specHasSignals) return;
    long emptyCount =
        plan.tasks().stream()
            .filter(t -> t.hardConstraints() == null || t.hardConstraints().isEmpty())
            .count();
    double emptyRatio = (double) emptyCount / plan.tasks().size();
    if (emptyRatio >= 0.6) {
      log.warn(
          "PlannerOutputUtils constraint-propagation QUALITY WARNING: spec contains "
              + "constraint signals but {}/{} tasks have empty hardConstraints. "
              + "Worker may not respect them.",
          emptyCount,
          plan.tasks().size());
    }
  }

  // Salvage / text helpers

  /**
   * Salvage a {@code submitPlan}/{@code submitOutline} call from text when the model emits it as
   * Python-style pseudo-code instead of a structured tool call. Returns a JSON args string the
   * parser can consume, or null if the text doesn't contain a recoverable call. Tool-name agnostic
   * — matches both {@code submitPlan(...)} and {@code submitOutline(...)}.
   */
  public static String salvagePlanFromText(String text) {
    if (text == null || text.isBlank()) return null;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "submit(?:Plan|Outline)\\s*\\(([\\s\\S]*)\\)\\s*$",
                java.util.regex.Pattern.MULTILINE)
            .matcher(text);
    if (!m.find()) return null;
    String args = m.group(1).trim();
    StringBuilder json = new StringBuilder("{");
    int depth = 0;
    int start = 0;
    List<String> pairs = new ArrayList<>();
    for (int i = 0; i < args.length(); i++) {
      char c = args.charAt(i);
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') depth--;
      else if (c == ',' && depth == 0) {
        pairs.add(args.substring(start, i).trim());
        start = i + 1;
      }
    }
    if (start < args.length()) pairs.add(args.substring(start).trim());
    for (int i = 0; i < pairs.size(); i++) {
      String pair = pairs.get(i);
      int eq = pair.indexOf('=');
      if (eq < 0) continue;
      String key = pair.substring(0, eq).trim();
      String val = pair.substring(eq + 1).trim();
      val =
          val.replaceAll("\\bTrue\\b", "true")
              .replaceAll("\\bFalse\\b", "false")
              .replaceAll("\\bNone\\b", "null");
      if (i > 0) json.append(",");
      json.append("\"").append(key).append("\":").append(val);
    }
    json.append("}");
    String candidate = json.toString();
    if (!candidate.contains("tasks")) return null;
    return candidate;
  }

  public static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }

  /**
   * Concise, title-cased run name from a free-form spec. Used by the SINGLE_SHOT path so
   * project-view tables show readable titles.
   */
  public static String cleanTitle(String spec, int max) {
    if (spec == null || spec.isBlank()) return "Untitled run";
    String s = spec.trim().replaceAll("\\s+", " ");
    int dot = s.indexOf('.');
    int nl = s.indexOf('\n');
    int cut = -1;
    if (dot > 0) cut = dot;
    if (nl > 0 && (cut < 0 || nl < cut)) cut = nl;
    if (cut > 0) s = s.substring(0, cut).trim();
    if (!s.isEmpty() && Character.isLowerCase(s.charAt(0))) {
      s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    while (!s.isEmpty() && ",;:.".indexOf(s.charAt(s.length() - 1)) >= 0) {
      s = s.substring(0, s.length() - 1);
    }
    if (s.length() > max) s = s.substring(0, max - 1).trim() + "…";
    return s.isBlank() ? "Untitled run" : s;
  }

  public static LlmProvider resolveProviderForTitle(String providerName) {
    if (providerName == null || providerName.isBlank()) return LlmProvider.OPENAI;
    String up = providerName.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    if (Arrays.stream(LlmProvider.values()).noneMatch(v -> v.name().equals(up))) {
      return LlmProvider.OPENAI;
    }
    try {
      return LlmProvider.valueOf(up);
    } catch (IllegalArgumentException e) {
      return LlmProvider.OPENAI;
    }
  }
}
