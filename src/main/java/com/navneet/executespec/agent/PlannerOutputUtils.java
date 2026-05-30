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
 * v2.6.2 slice 5b — shared parsing + validation helpers for the V2 planner pipeline. Extracted from
 * {@code PlannerAgent} so {@link OutlinePlannerAgent} (and its validation test) can use them
 * without depending on the legacy planner. The legacy class is now deleted; this is the single
 * source of truth for planner-output handling.
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
   * salvage path) and to skeleton-mode plans where descriptions / hardConstraints /
   * meetsRequirements are intentionally blank.
   */
  public static Plan parsePlanFromArgs(
      String argsJson, Classification classification, ObjectMapper objectMapper) {
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
        if (idx >= classification.maxTasks()) {
          log.warn(
              "PlannerOutputUtils: planner exceeded maxTasks={} for classification={}, truncating",
              classification.maxTasks(),
              classification);
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
   * clean. Skips rules that don't apply (e.g. skeleton plans where meetsRequirements is
   * intentionally blank pre- expansion are checked at the post-coherence pass instead, not here).
   */
  public static List<String> validatePlan(Plan plan) {
    List<String> out = new ArrayList<>();
    List<RequirementSpec> reqs = plan.requirements();
    if (reqs == null || reqs.isEmpty()) {
      out.add("No requirements enumerated (top-level 'requirements' array is empty).");
      return out;
    }

    Set<String> ids = new HashSet<>();
    Set<String> duplicates = new LinkedHashSet<>();
    for (RequirementSpec r : reqs) {
      if (r.id() == null || r.id().isBlank()) {
        out.add("Requirement has blank id (text=" + truncate(r.text(), 60) + ").");
        continue;
      }
      if (!ids.add(r.id())) duplicates.add(r.id());
    }
    if (!duplicates.isEmpty()) {
      out.add("Duplicate requirement ids: " + duplicates);
    }

    Set<String> covered = new HashSet<>();
    Set<String> unknownRefs = new LinkedHashSet<>();
    for (PlannedTask t : plan.tasks()) {
      if (t.meetsRequirements() == null) continue;
      for (String refId : t.meetsRequirements()) {
        if (!ids.contains(refId)) unknownRefs.add(refId);
        else covered.add(refId);
      }
    }
    if (!unknownRefs.isEmpty()) {
      out.add("Task meetsRequirements references unknown ids: " + unknownRefs);
    }
    Set<String> uncovered = new LinkedHashSet<>(ids);
    uncovered.removeAll(covered);
    if (!uncovered.isEmpty()) {
      out.add("Requirements not covered by any task: " + uncovered);
    }

    Plan.RuntimeSurface surface = plan.runtimeSurface();
    boolean testableSurface =
        surface == Plan.RuntimeSurface.HTTP
            || surface == Plan.RuntimeSurface.CLI
            || surface == Plan.RuntimeSurface.LIBRARY
            || surface == Plan.RuntimeSurface.MOBILE_APP;
    boolean isSingleShot = plan.classification() == Classification.SINGLE_SHOT;
    if (testableSurface && !isSingleShot) {
      boolean hasTestRequirement =
          reqs.stream().anyMatch(r -> r.kind() == RequirementSpec.RequirementKind.TEST);
      boolean hasTestTask =
          plan.tasks().stream()
              .anyMatch(
                  t ->
                      (t.title() != null && t.title().toLowerCase(Locale.ROOT).contains("test"))
                          || (t.description() != null
                              && t.description().toLowerCase(Locale.ROOT).contains("test")));
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
