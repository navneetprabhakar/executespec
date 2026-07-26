package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed requirement coverage checks for an assembled hierarchical plan.
 *
 * <p>An R-id citation is necessary but not sufficient: the union of the tasks claiming that
 * requirement must retain its high-signal contract vocabulary. The vocabulary is derived from the
 * current requirement, never from a previously generated application.
 */
final class PlanRequirementCoverageGate {
  private static final String REQUIREMENT_SIGNALS_PREFIX =
      "REQUIREMENT SIGNALS (binding; preserve these exact contract terms): ";
  private static final Pattern STRUCTURED_TERM =
      Pattern.compile(
          "\\b(?:[A-Z][A-Z0-9_]{2,}|[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+|"
              + "[A-Z][A-Za-z0-9]+-[A-Z][A-Za-z0-9-]+)\\b");
  private static final Pattern UNIQUE_SUBJECT =
      Pattern.compile("(?i)\\bunique\\s+([a-z][a-z0-9_-]{2,})");
  private static final Map<String, Pattern> CONTRACT_CONCEPTS = contractConcepts();

  private PlanRequirementCoverageGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.requirements() == null || plan.requirements().isEmpty()) return;
    List<PlannedTask> tasks = plan.tasks() == null ? List.of() : plan.tasks();
    for (RequirementSpec requirement : plan.requirements()) {
      if (requirement == null || safe(requirement.id()).isBlank()) continue;
      List<PlannedTask> owners =
          tasks.stream()
              .filter(java.util.Objects::nonNull)
              .filter(
                  task ->
                      task.meetsRequirements() != null
                          && task.meetsRequirements().contains(requirement.id()))
              .toList();
      if (owners.isEmpty()) {
        violations.add(
            requirement.id()
                + ": requirement is not cited by any task's meetsRequirements; it would be absent"
                + " from the worker execution contract");
        continue;
      }

      Set<String> markers = requirementMarkers(safe(requirement.text()));
      if (markers.isEmpty()) continue;
      List<PlannedTask> evidenceOwners = evidenceOwners(requirement, owners);
      String evidence =
          evidenceOwners.stream()
              .map(PlanRequirementCoverageGate::taskEvidence)
              .collect(java.util.stream.Collectors.joining("\n"))
              .toLowerCase(Locale.ROOT);
      List<String> missing =
          markers.stream().filter(marker -> !containsMarker(evidence, marker)).sorted().toList();
      if (!missing.isEmpty()) {
        violations.add(
            requirement.id()
                + ": tasks "
                + evidenceOwners.stream().map(PlannedTask::id).toList()
                + " cite the requirement but omit high-signal contract terms "
                + missing);
      }
    }
  }

  static Plan reinforceMissingMarkers(Plan plan) {
    if (plan == null
        || plan.requirements() == null
        || plan.requirements().isEmpty()
        || plan.tasks() == null
        || plan.tasks().isEmpty()) return plan;
    Map<String, List<String>> signalsByTask = new LinkedHashMap<>();
    for (RequirementSpec requirement : plan.requirements()) {
      if (requirement == null || safe(requirement.id()).isBlank()) continue;
      List<PlannedTask> owners =
          plan.tasks().stream()
              .filter(java.util.Objects::nonNull)
              .filter(
                  task ->
                      task.meetsRequirements() != null
                          && task.meetsRequirements().contains(requirement.id()))
              .toList();
      if (owners.isEmpty()) continue;
      Set<String> markers = requirementMarkers(safe(requirement.text()));
      if (markers.isEmpty()) continue;
      List<PlannedTask> evidenceOwners = evidenceOwners(requirement, owners);
      String evidence =
          evidenceOwners.stream()
              .map(PlanRequirementCoverageGate::taskEvidence)
              .collect(java.util.stream.Collectors.joining("\n"))
              .toLowerCase(Locale.ROOT);
      List<String> missing =
          markers.stream().filter(marker -> !containsMarker(evidence, marker)).sorted().toList();
      if (missing.isEmpty()) continue;
      String signal =
          requirement.id()
              + " requires "
              + missing.stream()
                  .map(PlanRequirementCoverageGate::markerSignal)
                  .collect(java.util.stream.Collectors.joining(", "));
      for (PlannedTask owner : evidenceOwners) {
        signalsByTask
            .computeIfAbsent(owner.id(), ignored -> new java.util.ArrayList<>())
            .add(signal);
      }
    }
    if (signalsByTask.isEmpty()) return plan;
    List<PlannedTask> tasks = new java.util.ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      List<String> signals = task == null ? null : signalsByTask.get(task.id());
      if (task == null || signals == null || signals.isEmpty()) {
        tasks.add(task);
        continue;
      }
      String description =
          TaskExpansionTextNormalizer.boundTextPreservingFinalContract(
              mergeRequirementSignals(safe(task.description()), signals),
              TaskExpanderAgent.MAX_DESCRIPTION_CHARS);
      tasks.add(copyTask(task, description));
    }
    return copyPlan(plan, tasks);
  }

  /**
   * Acceptance requirements must remain executable in their verification task. Allowing an
   * implementation owner to satisfy the union by merely mentioning "rollback" can otherwise erase
   * the assertion from the black-box suite while the plan still appears covered.
   */
  private static List<PlannedTask> evidenceOwners(
      RequirementSpec requirement, List<PlannedTask> owners) {
    String text = safe(requirement.text()).toLowerCase(Locale.ROOT);
    boolean verificationRequirement =
        text.matches(
            "(?s).*\\b(?:acceptance|black[- ]box|integration test|end[- ]to[- ]end|e2e|"
                + "verify|assert|proof)\\b.*");
    if (!verificationRequirement) return owners;
    List<PlannedTask> verificationOwners =
        owners.stream()
            .filter(owner -> !TaskExpansionPlanContract.isImplementationTask(owner))
            .toList();
    return verificationOwners.isEmpty() ? owners : verificationOwners;
  }

  static Set<String> requirementMarkers(String requirement) {
    String normalized = safe(requirement);
    LinkedHashSet<String> markers = new LinkedHashSet<>();
    Matcher structured = STRUCTURED_TERM.matcher(normalized);
    while (structured.find()) {
      String value = normalizeStructured(structured.group());
      if (!value.matches("r\\d+") && value.length() >= 3) markers.add(value);
    }
    Matcher unique = UNIQUE_SUBJECT.matcher(normalized);
    while (unique.find()) {
      markers.add("unique");
      String subject = normalizeStructured(unique.group(1));
      if (!Set.of("validated", "active", "required", "non_empty").contains(subject)) {
        markers.add(subject);
      }
    }
    String lower = normalized.toLowerCase(Locale.ROOT);
    CONTRACT_CONCEPTS.forEach(
        (concept, pattern) -> {
          if (pattern.matcher(lower).find()) markers.add(concept);
        });
    return Set.copyOf(markers);
  }

  private static boolean containsMarker(String evidence, String marker) {
    if (marker.equals("pagination")
        && evidence.matches(
            "(?s).*(?:\\bpage(?:_size)?\\b|\\boffset\\b|\\bskip\\b).*\\blimit\\b.*|"
                + ".*\\blimit\\b.*(?:\\bpage(?:_size)?\\b|\\boffset\\b|\\bskip\\b).*")) {
      return true;
    }
    if (marker.equals("migrated")
        && evidence.matches("(?s).*\\b(?:alembic|flyway|liquibase|migration runner)\\b.*")) {
      return true;
    }
    if (marker.equals("accessible")
        && evidence.matches("(?s).*\\b(?:aria-[a-z-]+|screen reader|keyboard navigation)\\b.*")) {
      return true;
    }
    if (marker.equals("loading")
        && evidence.matches("(?s).*\\b(?:spinner|skeleton|pending state)\\b.*")) {
      return true;
    }
    if (marker.equals("environment")
        && evidence.matches(
            "(?s).*\\b(?:configuration"
                + " variables?|[a-z][a-z0-9]+_(?:url|host|port|key|secret))\\b.*")) {
      return true;
    }
    if (marker.equals("sku") && evidence.matches("(?s).*\\b(?:sku|product(?:_id)?)\\b.*")) {
      return true;
    }
    Pattern concept = CONTRACT_CONCEPTS.get(marker);
    if (concept != null) return concept.matcher(evidence).find();
    String normalizedEvidence = normalizeStructured(evidence);
    return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(marker) + "(?![a-z0-9])")
        .matcher(normalizedEvidence)
        .find();
  }

  private static String markerSignal(String marker) {
    // Concept keys are internal identifiers, not executable contract language. In particular,
    // "exactly_once" does not satisfy the gate's phrase-level "exactly once" evidence check and
    // caused a fully expanded plan to fail after a second paid coherence pass. Structured schema
    // terms retain underscores because they may be exact field names.
    return CONTRACT_CONCEPTS.containsKey(marker) ? marker.replace('_', ' ') : marker;
  }

  private static String mergeRequirementSignals(String description, List<String> newSignals) {
    String source = safe(description).strip();
    LinkedHashSet<String> combined = new LinkedHashSet<>();
    int marker = source.lastIndexOf(REQUIREMENT_SIGNALS_PREFIX);
    if (marker >= 0) {
      int paragraphEnd = source.indexOf("\n\n", marker);
      if (paragraphEnd < 0) paragraphEnd = source.length();
      String existing =
          source
              .substring(marker + REQUIREMENT_SIGNALS_PREFIX.length(), paragraphEnd)
              .strip()
              .replaceFirst("\\.\\s*$", "");
      for (String value : existing.split(";\\s*")) {
        if (!value.isBlank()) combined.add(value.strip());
      }
      source = (source.substring(0, marker) + source.substring(paragraphEnd)).strip();
    }
    combined.addAll(newSignals);
    String binding = REQUIREMENT_SIGNALS_PREFIX + String.join("; ", combined) + ".";
    return source.isBlank() ? binding : source + "\n\n" + binding;
  }

  private static String taskEvidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints())
        + "\n"
        + String.join("\n", task.touchesFiles() == null ? List.of() : task.touchesFiles());
  }

  private static PlannedTask copyTask(PlannedTask source, String description) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(description)
        .complexity(source.complexity())
        .touchesFiles(source.touchesFiles())
        .dependsOn(source.dependsOn())
        .hardConstraints(source.hardConstraints())
        .meetsRequirements(source.meetsRequirements())
        .taskType(source.taskType())
        .build();
  }

  private static Plan copyPlan(Plan source, List<PlannedTask> tasks) {
    return Plan.builder()
        .planTitle(source.planTitle())
        .planSummary(source.planSummary())
        .projectType(source.projectType())
        .buildCommand(source.buildCommand())
        .smokeTestStrategy(source.smokeTestStrategy())
        .runtimeSurface(source.runtimeSurface())
        .tasks(tasks)
        .classification(source.classification())
        .requirements(source.requirements())
        .build();
  }

  private static String normalizeStructured(String value) {
    return safe(value)
        .toLowerCase(Locale.ROOT)
        .replace('-', '_')
        .replaceAll("[^a-z0-9_]+", " ")
        .trim();
  }

  private static Map<String, Pattern> contractConcepts() {
    Map<String, String> expressions = new LinkedHashMap<>();
    expressions.put("deactivate", "\\bdeactivat(?:e|es|ed|ing|ion)\\b");
    expressions.put(
        "unique",
        "\\buniqu(?:e|eness)\\b|\\bduplicate(?:s|d)?\\b[^.\\n]{0,100}"
            + "\\b(?:reject|conflict|integrity|constraint|409)\\b");
    expressions.put(
        "validate",
        "\\bvalidat(?:e|es|ed|ing|ion|ions)\\b|"
            + "\\b(?:reject|caught|constraint|integrityerror|valueerror)\\b");
    expressions.put("reason", "\\breasons?\\b");
    expressions.put("active", "(?<!in)\\bactive\\b");
    expressions.put("inactive", "\\binactive\\b");
    expressions.put("atomic", "\\batomic(?:ally|ity)?\\b");
    expressions.put("immutable", "\\bimmutab(?:le|ility)\\b");
    expressions.put("exactly_once", "\\bexactly\\s+once\\b");
    expressions.put("replay", "\\breplay(?:s|ed|ing)?\\b");
    expressions.put("conflict", "\\bconflict(?:s|ed|ing)?\\b");
    expressions.put("concurrent", "\\bconcurren(?:t|cy|tly)\\b");
    expressions.put("oversell", "\\boversell(?:s|ing)?\\b");
    expressions.put("rollback", "\\broll\\s*back\\b|\\brollback\\b");
    expressions.put("search", "\\bsearch(?:es|ed|ing|able)?\\b");
    expressions.put("filter", "\\bfilter(?:s|ed|ing|able)?\\b");
    expressions.put("pagination", "\\bpaginat(?:e|es|ed|ing|ion)\\b");
    expressions.put("responsive", "\\bresponsiv(?:e|eness)\\b");
    expressions.put("accessible", "\\baccessib(?:le|ility)\\b");
    expressions.put("loading", "\\bload(?:ing|s|ed)?\\b");
    expressions.put("empty", "\\bempty\\b");
    expressions.put("error", "\\berrors?\\b");
    expressions.put("success", "\\bsuccess(?:ful|fully)?\\b");
    expressions.put("fresh", "\\bfresh\\b");
    expressions.put("migrated", "\\bmigrat(?:e|es|ed|ing|ion|ions)\\b");
    expressions.put("architecture", "\\barchitectur(?:e|al)\\b");
    expressions.put("environment", "\\benvironment\\b|\\benv(?:ironment)?[_ -]?vars?\\b");
    expressions.put(
        "mock_only",
        "\\bmock[_ -]only\\b|\\breal\\s+(?:database|persistence|models?)\\b|"
            + "\\bpersist(?:s|ed|ence|ent)?\\b");
    expressions.put(
        "hard_coded",
        "\\bhard[_ -]cod(?:e|ed|ing)\\b|\\b(?:credential|secret)s?\\b[^.\\n]{0,100}"
            + "\\b(?:environment|configur(?:e|ed|ation)|runtime)\\b");
    expressions.put("limitations", "\\blimitations?\\b");
    expressions.put("reserve", "\\breserv(?:e|es|ed|ing|ation|ations)\\b");
    expressions.put("commit", "\\bcommit(?:s|ted|ting)?\\b");
    expressions.put("cancel", "\\bcancel(?:s|led|ing|lation)?\\b");
    expressions.put("transfer", "\\btransfer(?:s|red|ring)?\\b");
    expressions.put("adjustment", "\\badjust(?:s|ed|ing|ment|ments)?\\b");
    Map<String, Pattern> result = new LinkedHashMap<>();
    expressions.forEach(
        (key, expression) ->
            result.put(key, Pattern.compile(expression, Pattern.CASE_INSENSITIVE)));
    return Map.copyOf(result);
  }
}
