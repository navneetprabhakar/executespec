package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Domain-neutral checks derived from the assembled plan. Never encode product-specific contracts
// learned from a previously tested application.
final class PlanContractQualityGate {
  private static final Pattern UNRESOLVED_IMPLEMENTATION_CHOICE =
      Pattern.compile(
          "(?i)\\b(?:pick|choose|select|decide\\s+on)\\s+(?:one|either)\\b|"
              + "\\b(?:pick|choose)\\b|"
              + "\\bselect\\s+(?:(?:a|an|the)\\s+)?(?:stack|driver|framework|library|database|"
              + "orm|language|test runner|tooling|strategy|approach|implementation|"
              + "synchronous|asynchronous|sync|async)(?:\\s+implementation)?"
              + "(?=\\s+(?:or|versus|vs\\.?|/))");
  private static final Pattern IMPLEMENTATION_ALTERNATIVE =
      Pattern.compile(
          "(?i)\\b(?:or|either)\\b|\\b(?:stack|driver|framework|library|database|orm|language|"
              + "test runner|tooling|strategy|approach|implementation|synchronous|asynchronous|"
              + "sync|async)\\b");
  private static final Pattern ARTIFACT =
      Pattern.compile(
          "(?i)(?:^|[/`'\"\\s])([a-z0-9][a-z0-9_.-]{2,}\\.(?:py|sql|xml|yaml|yml|json))");
  private static final Pattern NAMED_MODEL =
      Pattern.compile("(?i)(?:\\*\\*|`)?([a-z][a-z0-9_]*)\\s+(?:model|table)(?:\\*\\*|`)?");
  private static final Pattern BOLD_MODEL_DEFINITION =
      Pattern.compile("\\*\\*([A-Z][A-Za-z0-9_]*)\\*\\*\\s*(?:—|-|:)");
  private static final Pattern PLAIN_MODEL_DEFINITION =
      Pattern.compile(
          "(?i)\\b(?:define|create|implement)\\s+(?:the\\s+)?([A-Z][A-Za-z0-9_]*)"
              + "\\s+(?:model|entity)\\b");
  private static final Pattern MODEL_ARTIFACT =
      Pattern.compile(
          "(?i)(?:^|/)((?:models?|entities)/([a-z0-9_-]+)\\.(?:py|java|kt|ts|tsx|js|jsx|cs|go|rb|php))$");

  private PlanContractQualityGate() {}

  static List<String> violations(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return List.of();
    List<String> violations = new ArrayList<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      addTaskInstructionViolations(task, violations);
      TaskExpansionContradictionValidator.addViolations(task, plan, violations);
    }
    PlanWorkerBriefIntegrityGate.addViolations(plan, violations);
    addSharedArtifactViolations(plan.tasks(), violations);
    addModelOwnershipViolations(plan.tasks(), violations);
    PlanModelDefinitionOwnershipGate.addViolations(plan, violations);
    PlanCrossTaskSymbolReferenceGate.addViolations(plan, violations);
    PlanLifecycleValueContractGate.addViolations(plan.tasks(), violations);
    PlanSharedFieldContractGate.addViolations(plan.tasks(), violations);
    PlanPersistenceFieldUsageGate.addViolations(plan, violations);
    // The database-initialization contradiction gate was removed (2026-08-08 Class-B refinement).
    // It re-checked the deterministic PlanSafetyContractEnricher's own migration/create_all output
    // in natural-language prose and false-positived on correctly-enriched plans (dev runs 3ef2716f
    // T3/T4). The genuine "task bypasses migrations with ORM/manual create_all" contradiction is
    // caught more precisely by TaskExpansionContradictionValidator (constraint forbids create_all
    // AND the description positively instructs it), which already runs above.
    addPythonPackagingViolations(plan.tasks(), violations);
    PlanBackendContractGate.addViolations(plan, violations);
    PlanCrossStackIdentifierTypeGate.addViolations(plan, violations);
    PlanGlobalErrorEnvelopeGate.addViolations(plan, violations);
    PlanExecutableTaskGate.addViolations(plan, violations);
    PlanE2eToolchainGate.addViolations(plan, violations);
    PlanRequirementCoverageGate.addViolations(plan, violations);
    PlanApiRequestContractGate.addViolations(plan, violations);
    PlanApiArtifactOwnershipGate.addViolations(plan, violations);
    PlanScopeOwnershipReconciler.addViolations(plan, violations);
    PlanFrontendRouteBindingGate.addViolations(plan, violations);
    PlanLifecycleJourneyGate.addViolations(plan, violations);
    PlanJourneyCardinalityGate.addViolations(plan, violations);
    PlanApiSyntaxGate.addViolations(plan, violations);
    PlanApiBindingContradictionGate.addViolations(plan, violations);
    PlanApiRouteIdentityContract.addViolations(plan, violations);
    PlanApiContractReconciler.addViolations(plan, violations);
    PlanDocumentedCommandContractGate.addViolations(plan, violations);
    return List.copyOf(new LinkedHashSet<>(violations));
  }

  private static void addModelOwnershipViolations(
      List<PlannedTask> tasks, List<String> violations) {
    java.util.Map<String, String> uniqueOwnerByModelFile = new java.util.LinkedHashMap<>();
    java.util.Map<String, String> displayPathByModelFile = new java.util.LinkedHashMap<>();
    java.util.Map<String, Integer> ownerCountByModelFile = new java.util.LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      for (String raw : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String path = safe(raw).replace('\\', '/').toLowerCase(Locale.ROOT);
        Matcher modelFile = MODEL_ARTIFACT.matcher(path);
        if (!modelFile.find()
            || PlanArtifactOwnershipSupport.isPackageOrRegistryMetadata(path)
            || Set.of("base", "model", "models", "entity", "entities").contains(modelFile.group(2)))
          continue;
        String model = toSnakeCase(modelFile.group(2));
        ownerCountByModelFile.merge(model, 1, Integer::sum);
        uniqueOwnerByModelFile.put(model, task.id());
        displayPathByModelFile.put(model, modelFile.group(1));
      }
    }
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      String description = safe(task.description());
      Matcher definition = BOLD_MODEL_DEFINITION.matcher(description);
      while (definition.find()) {
        String lead =
            description
                .substring(Math.max(0, definition.start() - 240), definition.start())
                .toLowerCase(Locale.ROOT);
        if (!lead.matches("(?s).*\\b(?:define|create|implement|model)s?\\b.*")) continue;
        addForeignModelDefinitionViolation(
            task,
            definition.group(1),
            ownerCountByModelFile,
            uniqueOwnerByModelFile,
            displayPathByModelFile,
            violations);
      }
      Matcher plainDefinition = PLAIN_MODEL_DEFINITION.matcher(description);
      while (plainDefinition.find()) {
        addForeignModelDefinitionViolation(
            task,
            plainDefinition.group(1),
            ownerCountByModelFile,
            uniqueOwnerByModelFile,
            displayPathByModelFile,
            violations);
      }
    }
  }

  private static void addForeignModelDefinitionViolation(
      PlannedTask task,
      String displayModel,
      java.util.Map<String, Integer> ownerCountByModelFile,
      java.util.Map<String, String> uniqueOwnerByModelFile,
      java.util.Map<String, String> displayPathByModelFile,
      List<String> violations) {
    String model = toSnakeCase(displayModel);
    if (ownerCountByModelFile.getOrDefault(model, 0) != 1) return;
    String owner = uniqueOwnerByModelFile.get(model);
    if (java.util.Objects.equals(owner, task.id())) return;
    violations.add(
        task.id()
            + ": description defines model "
            + displayModel
            + " even though "
            + owner
            + " uniquely owns "
            + displayPathByModelFile.get(model));
  }

  private static String toSnakeCase(String value) {
    return value
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .replace('-', '_')
        .toLowerCase(Locale.ROOT);
  }

  private static void addTaskInstructionViolations(PlannedTask task, List<String> violations) {
    String evidence = taskEvidence(task);
    if (hasUnresolvedImplementationChoice(evidence)) {
      violations.add(
          task.id()
              + ": executable task leaves an implementation choice unresolved; select one"
              + " concrete stack or strategy before worker execution");
    }

    Matcher modelMatcher = NAMED_MODEL.matcher(evidence);
    while (modelMatcher.find()) {
      String entity = modelMatcher.group(1);
      Pattern response =
          Pattern.compile(
              "(?is)\\b"
                  + Pattern.quote(entity)
                  + "(?:Response|DTO|Dto)\\s*\\([^)]*\\b(status|state)\\b[^)]*\\)");
      Matcher responseMatcher = response.matcher(evidence);
      int modelSectionEnd = Math.min(evidence.length(), modelMatcher.start() + 700);
      if (!responseMatcher.find(modelMatcher.end()) || responseMatcher.start() > modelSectionEnd)
        continue;
      String responseField = responseMatcher.group(1).toLowerCase(Locale.ROOT);
      String modelSection = evidence.substring(modelMatcher.start(), responseMatcher.start());
      boolean modelHasState = modelSection.matches("(?is).*\\bstate\\b.*");
      boolean modelHasStatus = modelSection.matches("(?is).*\\bstatus\\b.*");
      if ((modelHasState && responseField.equals("status") && !modelHasStatus)
          || (modelHasStatus && responseField.equals("state") && !modelHasState)) {
        violations.add(
            task.id()
                + ": "
                + entity
                + " persistence and response schemas alternate between state and status without"
                + " an explicit mapping");
      }
    }
  }

  private static boolean hasUnresolvedImplementationChoice(String evidence) {
    Matcher matcher = UNRESOLVED_IMPLEMENTATION_CHOICE.matcher(evidence);
    while (matcher.find()) {
      String localSql =
          evidence.substring(
              Math.max(0, matcher.start() - 24), Math.min(evidence.length(), matcher.end() + 64));
      if (matcher.group().equalsIgnoreCase("select")
          && localSql.matches("(?is).*\\bselect\\s+for\\s+update\\b.*")) {
        continue;
      }
      String clause = localClause(evidence, matcher.start(), matcher.end());
      if (matcher.group().toLowerCase(Locale.ROOT).startsWith("select")
          && clause.matches(
              "(?is).*\\b(?:query|row|record|source|target|column|field|inventory|warehouse|"
                  + "product|order)\\b.*")
          && !clause.matches(
              "(?is).*\\b(?:stack|driver|framework|library|database|orm|language|test runner|"
                  + "tooling|strategy|approach|implementation|synchronous|asynchronous|sync|"
                  + "async)\\b.*")) {
        continue;
      }
      if (matcher.group().equalsIgnoreCase("select")
          && clause.matches("(?is).*\\b(?:input|filter|control|dropdown|option|field|form)\\b.*")
          && !clause.matches(
              "(?is).*\\b(?:stack|driver|framework|library|database|orm|language|test runner|"
                  + "tooling|strategy|approach|implementation|synchronous|asynchronous|sync|"
                  + "async)\\b.*")) {
        // HTML/UI controls routinely say "text input or select". That is a presentation detail,
        // not an unresolved technology or execution strategy.
        continue;
      }
      if (!IMPLEMENTATION_ALTERNATIVE.matcher(clause).find()
          || !clause.matches("(?is).*\\bor\\b.*")) continue;
      String following =
          evidence.substring(matcher.end(), Math.min(evidence.length(), matcher.end() + 360));
      boolean explicitlyResolved =
          following.matches(
              "(?is).*(?:given|therefore|decision|selected)[^.\\n]{0,220}"
                  + "\\b(?:use|implement|select)\\b[^.\\n]*.*");
      if (!explicitlyResolved) return true;
    }
    return false;
  }

  private static void addSharedArtifactViolations(
      List<PlannedTask> tasks, List<String> violations) {
    java.util.Map<String, List<ArtifactEvidence>> byArtifact = new java.util.LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      String evidence = taskEvidence(task);
      Matcher matcher = ARTIFACT.matcher(evidence);
      Set<String> seen = new LinkedHashSet<>();
      while (matcher.find()) {
        String artifact = matcher.group(1).toLowerCase(Locale.ROOT);
        if (!seen.add(artifact)) continue;
        String localContext = localClause(evidence, matcher.start(), matcher.end());
        byArtifact
            .computeIfAbsent(artifact, ignored -> new ArrayList<>())
            .add(
                new ArtifactEvidence(
                    task.id(),
                    localContext.matches("(?is).*\\breversible\\b.*"),
                    localContext.matches("(?is).*\\bforward-only\\b.*")));
      }
    }
    byArtifact.forEach(
        (artifact, owners) -> {
          ArtifactEvidence reversible =
              owners.stream().filter(ArtifactEvidence::reversible).findFirst().orElse(null);
          ArtifactEvidence forwardOnly =
              owners.stream().filter(ArtifactEvidence::forwardOnly).findFirst().orElse(null);
          if (reversible != null
              && forwardOnly != null
              && !reversible.taskId().equals(forwardOnly.taskId())) {
            violations.add(
                forwardOnly.taskId()
                    + ": shared artifact "
                    + artifact
                    + " is called forward-only but "
                    + reversible.taskId()
                    + " requires it to be reversible");
          }
        });
  }

  private static String localClause(String evidence, int start, int end) {
    int clauseStart =
        Math.max(
            0, Math.max(evidence.lastIndexOf('\n', start), previousSentenceEnd(evidence, start)));
    int newlineEnd = evidence.indexOf('\n', end);
    int sentenceEnd = nextSentenceEnd(evidence, end);
    int clauseEnd = evidence.length();
    if (newlineEnd >= 0) clauseEnd = Math.min(clauseEnd, newlineEnd);
    if (sentenceEnd >= 0) clauseEnd = Math.min(clauseEnd, sentenceEnd + 1);
    return evidence.substring(clauseStart, clauseEnd);
  }

  private static int previousSentenceEnd(String evidence, int before) {
    int candidate = evidence.lastIndexOf('.', Math.max(0, before - 1));
    while (candidate >= 0) {
      if (isSentenceEnd(evidence, candidate)) return candidate;
      candidate = evidence.lastIndexOf('.', candidate - 1);
    }
    return -1;
  }

  private static int nextSentenceEnd(String evidence, int from) {
    int candidate = evidence.indexOf('.', from);
    while (candidate >= 0) {
      if (isSentenceEnd(evidence, candidate)) return candidate;
      candidate = evidence.indexOf('.', candidate + 1);
    }
    return -1;
  }

  private static boolean isSentenceEnd(String evidence, int period) {
    return period + 1 >= evidence.length() || Character.isWhitespace(evidence.charAt(period + 1));
  }

  private static void addPythonPackagingViolations(
      List<PlannedTask> tasks, List<String> violations) {
    boolean ownsRequirements =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(
                task ->
                    task.touchesFiles() == null
                        ? java.util.stream.Stream.empty()
                        : task.touchesFiles().stream())
            .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
            .anyMatch(
                path -> path.equals("requirements.txt") || path.endsWith("/requirements.txt"));
    boolean ownsPyproject =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(
                task ->
                    task.touchesFiles() == null
                        ? java.util.stream.Stream.empty()
                        : task.touchesFiles().stream())
            .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
            .anyMatch(path -> path.equals("pyproject.toml") || path.endsWith("/pyproject.toml"));
    if (ownsPyproject && !ownsRequirements) {
      for (PlannedTask task : tasks) {
        if (task == null) continue;
        String evidence = taskEvidence(task);
        if (evidence.matches("(?is).*pip\\s+install\\s+-r\\s+[^\\s`]*requirements\\.txt.*")) {
          violations.add(
              task.id()
                  + ": setup installs requirements.txt, but the plan owns pyproject.toml and no"
                  + " requirements.txt artifact");
        }
      }
    }
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      String evidence = taskEvidence(task).toLowerCase(Locale.ROOT);
      if (evidence.contains("create_async_engine")
          && evidence.contains("sqlite:///")
          && !evidence.contains("sqlite+aiosqlite:///")) {
        violations.add(
            task.id()
                + ": async SQLAlchemy engine is paired with a synchronous sqlite:/// URL; use an"
                + " async driver URL consistently or select the synchronous SQLAlchemy stack");
      }
      for (String pyproject :
          tasks.stream()
              .filter(java.util.Objects::nonNull)
              .flatMap(
                  candidate ->
                      candidate.touchesFiles() == null
                          ? java.util.stream.Stream.empty()
                          : candidate.touchesFiles().stream())
              .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
              .filter(path -> path.endsWith("pyproject.toml"))
              .toList()) {
        int slash = pyproject.lastIndexOf('/');
        if (slash <= 0) continue;
        String directory = pyproject.substring(0, slash);
        if (evidence.matches(
            "(?s).*\\bcd\\s+"
                + Pattern.quote(directory)
                + "\\s*&&\\s*pip\\s+install\\s+-e\\s+"
                + Pattern.quote(directory)
                + "(?:\\s|`|$).*")) {
          violations.add(
              task.id()
                  + ": editable install repeats "
                  + directory
                  + " after changing into that directory; use `pip install -e .` there");
        }
      }
    }
  }

  private static String taskEvidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  /**
   * Aligns literal consumer paths and typed query-parameter blocks with the nearest matching
   * producer. This is a bounded compatibility repair, not a domain schema generator.
   */
  static Plan reconcileConsumers(Plan plan) {
    return PlanApiContractReconciler.reconcileConsumers(plan);
  }

  /**
   * Promotes a consumer query parameter into the owning producer contract only when the
   * authoritative requirement names that parameter. This keeps a required filter from being
   * silently deleted merely because an expander omitted it from the backend task.
   */
  static Plan reconcileRequirementBackedProducerQueries(Plan plan) {
    return PlanApiContractReconciler.reconcileRequirementBackedProducerQueries(plan);
  }

  static Plan reconcilePackaging(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    String pyproject =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(
                task ->
                    task.touchesFiles() == null
                        ? java.util.stream.Stream.empty()
                        : task.touchesFiles().stream())
            .map(path -> safe(path).replace('\\', '/'))
            .filter(path -> path.equals("pyproject.toml") || path.endsWith("/pyproject.toml"))
            .findFirst()
            .orElse(null);
    boolean requirementsOwned =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(
                task ->
                    task.touchesFiles() == null
                        ? java.util.stream.Stream.empty()
                        : task.touchesFiles().stream())
            .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
            .anyMatch(
                path -> path.equals("requirements.txt") || path.endsWith("/requirements.txt"));
    if (pyproject == null || requirementsOwned) return plan;
    int slash = pyproject.lastIndexOf('/');
    String installTarget = slash < 0 ? "." : pyproject.substring(0, slash);
    String replacement = "pip install -e " + installTarget;
    Pattern unownedRequirementsInstall =
        Pattern.compile("(?i)pip\\s+install\\s+-r\\s+[^\\s`]*requirements\\.txt");
    String workingDirectory = slash < 0 ? "" : pyproject.substring(0, slash);
    Pattern repeatedEditableTarget =
        workingDirectory.isBlank()
            ? null
            : Pattern.compile(
                "(?i)(\\bcd\\s+"
                    + Pattern.quote(workingDirectory)
                    + "\\s*&&\\s*pip\\s+install\\s+-e\\s+)"
                    + Pattern.quote(workingDirectory)
                    + "(?=\\s|`|$)");
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        tasks.add(null);
        continue;
      }
      String description =
          unownedRequirementsInstall
              .matcher(safe(task.description()))
              .replaceAll(Matcher.quoteReplacement(replacement));
      if (repeatedEditableTarget != null) {
        description = repeatedEditableTarget.matcher(description).replaceAll("$1.");
      }
      List<String> constraints =
          (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
              .stream()
                  .map(
                      constraint ->
                          repairRepeatedEditableTarget(
                              unownedRequirementsInstall
                                  .matcher(constraint)
                                  .replaceAll(Matcher.quoteReplacement(replacement)),
                              repeatedEditableTarget))
                  .toList();
      tasks.add(copyTask(task, description, constraints));
    }
    return copyPlan(plan, tasks);
  }

  private static String repairRepeatedEditableTarget(String value, Pattern pattern) {
    return pattern == null ? value : pattern.matcher(value).replaceAll("$1.");
  }

  private static PlannedTask copyTask(
      PlannedTask source, String description, List<String> constraints) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(description)
        .complexity(source.complexity())
        .touchesFiles(source.touchesFiles())
        .dependsOn(source.dependsOn())
        .hardConstraints(constraints)
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

  private record ArtifactEvidence(String taskId, boolean reversible, boolean forwardOnly) {}
}
