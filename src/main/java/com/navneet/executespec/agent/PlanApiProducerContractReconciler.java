package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.derivedRouterPrefix;
import static com.navneet.executespec.agent.PlanApiContractEvidence.displayPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.extract;
import static com.navneet.executespec.agent.PlanApiContractEvidence.mergeEvidence;
import static com.navneet.executespec.agent.PlanApiContractEvidence.normalizedPath;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Adds consumer-required operations to the unique backend task that owns their route prefix. */
final class PlanApiProducerContractReconciler {
  private static final String PRODUCER_BINDING_PREFIX =
      "API PRODUCER CONTRACTS (binding; implement every operation): ";
  private static final Pattern GENERATED_PRODUCER_BINDING =
      Pattern.compile(
          "(?im)^API PRODUCER CONTRACTS \\(binding; implement every operation\\):\\s*([^\\r"
              + "\\n"
              + "]+)$");
  private static final Pattern BOUND_OPERATION =
      Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/api[a-z0-9_{}./:-]*)");

  private PlanApiProducerContractReconciler() {}

  static Plan reconcileMissingOperations(Plan plan) {
    return reconcileMissingOperations(plan, false);
  }

  static Plan reconcileMissingReadOperations(Plan plan) {
    return reconcileMissingOperations(plan, true);
  }

  private static Plan reconcileMissingOperations(Plan plan, boolean structuralReadsOnly) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    plan = reconcileGeneratedBindingsToRouteArtifactOwners(plan);
    List<PlannedTask> backendTasks =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(task -> !isFrontendOnlyTask(task) && !isTestOnlyTask(task))
            .filter(PlanApiContractEvidence::isImplementationProducerTask)
            .toList();
    List<ApiEvidence> producers =
        mergeEvidence(backendTasks.stream().flatMap(task -> extract(task).stream()).toList());
    java.util.Map<String, LinkedHashSet<String>> bindingsByOwner = new java.util.LinkedHashMap<>();
    for (PlannedTask consumerTask : plan.tasks()) {
      if (consumerTask == null
          || (!isFrontendOnlyTask(consumerTask) && !isTestOnlyTask(consumerTask))) continue;
      for (ApiEvidence consumer : extractConsumerEvidence(consumerTask)) {
        boolean exactOwnedMutation =
            structuralReadsOnly
                && !consumer.method().equals("GET")
                && producers.stream()
                    .noneMatch(candidate -> candidate.path().equals(consumer.path()))
                && backendTasks.stream()
                        .filter(candidate -> declaresExactPath(candidate, consumer.path()))
                        .limit(2)
                        .count()
                    == 1;
        if (structuralReadsOnly && !consumer.method().equals("GET") && !exactOwnedMutation)
          continue;
        if (hasExactProducer(producers, consumer)) continue;
        if (hasDistinctCancelReleaseAction(producers, consumer)) continue;
        boolean hasRelatedProducer =
            producers.stream()
                .anyMatch(
                    candidate ->
                        candidate.method().equals(consumer.method())
                            && PlanApiContractEvidence.related(candidate, consumer));
        PlannedTask allocatedCollectionOwner =
            consumer.method().equals("GET") && !hasRelatedProducer
                ? uniqueAllocatedCollectionOwner(backendTasks, consumerTask, consumer)
                : null;
        if (allocatedCollectionOwner != null) {
          bindingsByOwner
              .computeIfAbsent(allocatedCollectionOwner.id(), ignored -> new LinkedHashSet<>())
              .add(consumer.method() + " " + displayPath(consumer.path()));
          continue;
        }
        List<PlannedTask> strongOwners =
            backendTasks.stream()
                .filter(
                    candidate ->
                        structuralReadsOnly
                            ? exactOwnedMutation
                                ? declaresExactPath(candidate, consumer.path())
                                : stronglyOwnsStructuralReadRoute(candidate, consumer)
                            : stronglyOwnsRoute(candidate, consumer))
                .toList();
        List<PlannedTask> owners = strongOwners;
        if (owners.isEmpty()) {
          // A consumer must not manufacture a second public read shape merely because one backend
          // task owns the same resource noun. If the assembled backend already exposes that noun
          // at another path (for example a nested collection), the difference is a real API-design
          // decision. Leave it visible for consumer reconciliation / fail-closed validation. A
          // concrete owner of the consumer's route family still wins above; this guard applies
          // only to the weaker terminal-noun fallback.
          if (hasConflictingReadShape(producers, consumer)) continue;
          owners =
              backendTasks.stream()
                  .filter(
                      candidate ->
                          structuralReadsOnly
                              ? weaklyOwnsStructuralReadRoute(candidate, consumer)
                              : weaklyOwnsRoute(candidate, consumer))
                  .toList();
        }
        if (owners.size() != 1) continue;
        bindingsByOwner
            .computeIfAbsent(owners.getFirst().id(), ignored -> new LinkedHashSet<>())
            .add(consumer.method() + " " + displayPath(consumer.path()));
      }
    }
    if (bindingsByOwner.isEmpty()) return plan;
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      LinkedHashSet<String> operations = task == null ? null : bindingsByOwner.get(task.id());
      if (task == null || operations == null || operations.isEmpty()) {
        tasks.add(task);
        continue;
      }
      String binding = producerBinding(operations);
      String description =
          TaskExpansionTextNormalizer.boundTextPreservingFinalContract(
              safe(task.description()) + "\n\n" + binding, TaskExpanderAgent.MAX_DESCRIPTION_CHARS);
      tasks.add(
          copyTask(
              task,
              description,
              bindProducerContract(task.hardConstraints(), operations, binding)));
    }
    return copyPlan(plan, tasks);
  }

  /**
   * Moves server-generated producer bindings to the task that owns the concrete route artifact.
   *
   * <p>A coherence repair can repeat a consumer operation in an adjacent implementation task. If
   * that repetition is later treated as authoritative, the deterministic closer can make the wrong
   * task a phantom producer while the real router file remains owned elsewhere. Only the
   * recognisable server-generated binding line is moved; provider-authored endpoint prose remains
   * visible to the fail-closed ownership gate.
   */
  static Plan reconcileGeneratedBindingsToRouteArtifactOwners(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    Map<String, String> ownerByResource = uniqueRouteArtifactOwnerIds(plan.tasks());
    if (ownerByResource.isEmpty()) return plan;
    Map<String, LinkedHashSet<String>> relocated = new java.util.LinkedHashMap<>();
    List<PlannedTask> rewritten = new ArrayList<>(plan.tasks().size());
    boolean changed = false;
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        rewritten.add(null);
        continue;
      }
      String description =
          rewriteGeneratedBindings(task.description(), task.id(), ownerByResource, relocated);
      List<String> constraints = new ArrayList<>();
      for (String constraint :
          task.hardConstraints() == null ? List.<String>of() : task.hardConstraints()) {
        String rewrittenConstraint =
            rewriteGeneratedBindings(constraint, task.id(), ownerByResource, relocated).strip();
        if (!rewrittenConstraint.isBlank() && !constraints.contains(rewrittenConstraint)) {
          constraints.add(rewrittenConstraint);
        }
      }
      changed |=
          !description.equals(safe(task.description()))
              || !constraints.equals(task.hardConstraints());
      rewritten.add(copyTask(task, description, constraints));
    }
    if (!changed && relocated.isEmpty()) return plan;
    List<PlannedTask> rebound = new ArrayList<>(rewritten.size());
    for (PlannedTask task : rewritten) {
      LinkedHashSet<String> operations = task == null ? null : relocated.get(task.id());
      if (task == null || operations == null || operations.isEmpty()) {
        rebound.add(task);
        continue;
      }
      String binding = producerBinding(operations);
      String description =
          TaskExpansionTextNormalizer.boundTextPreservingFinalContract(
              safe(task.description()).strip() + "\n\n" + binding,
              TaskExpanderAgent.MAX_DESCRIPTION_CHARS);
      rebound.add(
          copyTask(
              task,
              description,
              bindProducerContract(task.hardConstraints(), operations, binding)));
    }
    return copyPlan(plan, rebound);
  }

  private static String rewriteGeneratedBindings(
      String raw,
      String taskId,
      Map<String, String> ownerByResource,
      Map<String, LinkedHashSet<String>> relocated) {
    String value = safe(raw);
    Matcher matcher = GENERATED_PRODUCER_BINDING.matcher(value);
    StringBuffer rewritten = new StringBuffer();
    while (matcher.find()) {
      LinkedHashSet<String> retained = new LinkedHashSet<>();
      Matcher operation = BOUND_OPERATION.matcher(matcher.group(1));
      while (operation.find()) {
        String rendered =
            operation.group(1).toUpperCase(Locale.ROOT)
                + " "
                + operation.group(2).replaceAll("[.,;]+$", "");
        String owner =
            ownerByResource.get(routeResource(rendered.substring(rendered.indexOf(' ') + 1)));
        if (owner != null && !owner.equals(taskId)) {
          relocated.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(rendered);
        } else {
          retained.add(rendered);
        }
      }
      String replacement = retained.isEmpty() ? "" : producerBinding(retained);
      matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(rewritten);
    return rewritten.toString().replaceAll("\\n{3,}", "\n\n").strip();
  }

  private static String producerBinding(Set<String> operations) {
    return PRODUCER_BINDING_PREFIX + String.join(", ", operations) + ".";
  }

  static Map<String, String> uniqueRouteArtifactOwnerIds(List<PlannedTask> tasks) {
    Map<String, LinkedHashSet<String>> owners = new java.util.LinkedHashMap<>();
    for (PlannedTask task : tasks == null ? List.<PlannedTask>of() : tasks) {
      if (task == null || isFrontendOnlyTask(task) || isTestOnlyTask(task)) continue;
      for (String rawPath : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String path = safe(rawPath).replace('\\', '/').toLowerCase(Locale.ROOT);
        Matcher matcher =
            Pattern.compile("(?:^|/)(?:api|routers|routes|controllers)/([a-z0-9_-]+)\\.[a-z0-9]+$")
                .matcher(path);
        if (!matcher.find()) continue;
        String resource = matcher.group(1).replace('_', '-');
        if (Set.of("index", "main", "health", "common", "__init__").contains(resource)) continue;
        owners.computeIfAbsent(resource, ignored -> new LinkedHashSet<>()).add(task.id());
      }
    }
    Map<String, String> unique = new java.util.LinkedHashMap<>();
    owners.forEach(
        (resource, taskIds) -> {
          if (taskIds.size() == 1) unique.put(resource, taskIds.getFirst());
        });
    return Map.copyOf(unique);
  }

  static String routeResource(String rawPath) {
    String normalized = normalizedPath(rawPath);
    for (String segment : normalized.split("/")) {
      if (!segment.isBlank() && !segment.startsWith("{")) return segment.replace('_', '-');
    }
    return "";
  }

  private static boolean hasExactProducer(List<ApiEvidence> producers, ApiEvidence consumer) {
    return producers.stream()
        .anyMatch(
            producer ->
                producer.method().equals(consumer.method())
                    && producer.path().equals(consumer.path()));
  }

  private static boolean hasConflictingReadShape(
      List<ApiEvidence> producers, ApiEvidence consumer) {
    if (!consumer.method().equals("GET")) return false;
    String terminal = terminalResource(consumer.path());
    if (terminal.isBlank()) return false;
    return producers.stream()
        .filter(producer -> producer.method().equals("GET"))
        .filter(producer -> !producer.path().equals(consumer.path()))
        // Collection and item reads in one route family are complementary, not conflicting.
        .filter(producer -> !routeFamily(producer.path()).equals(routeFamily(consumer.path())))
        .map(ApiEvidence::path)
        .map(PlanApiProducerContractReconciler::terminalResource)
        .anyMatch(terminal::equals);
  }

  /**
   * Resolves a missing collection read to the uniquely allocated backend dependency.
   *
   * <p>Hierarchical plans often put several related collection reads in one backend API task and
   * allocate a frontend task to that producer through {@code dependsOn}. A late frontend expansion
   * can then name one additional collection path even though the backend task already owns the
   * resource and other top-level collection routes. Treat that allocation as stronger than the weak
   * terminal-noun fallback, but only when exactly one direct backend dependency both mentions the
   * route's domain and already declares a top-level collection GET. A nested-only route such as
   * {@code /accounts/{id}/events} therefore cannot manufacture an unrelated {@code /events} alias.
   */
  private static PlannedTask uniqueAllocatedCollectionOwner(
      List<PlannedTask> backendTasks, PlannedTask consumerTask, ApiEvidence consumer) {
    if (!consumer.method().equals("GET") || pathParameterCount(consumer.path()) != 0) return null;
    Set<String> dependencies =
        new LinkedHashSet<>(
            consumerTask.dependsOn() == null ? List.<String>of() : consumerTask.dependsOn());
    if (dependencies.isEmpty()) return null;
    Set<String> consumerTokens = PlanApiContractEvidence.domainTokens(consumer.path());
    if (consumerTokens.isEmpty()) return null;
    List<PlannedTask> candidates =
        backendTasks.stream()
            .filter(task -> dependencies.contains(task.id()))
            .filter(PlanApiProducerContractReconciler::declaresTopLevelCollectionRead)
            .filter(
                task -> {
                  String evidence =
                      safe(task.title())
                          + " "
                          + safe(task.description())
                          + " "
                          + String.join(
                              " ", task.touchesFiles() == null ? List.of() : task.touchesFiles());
                  Set<String> ownerTokens = PlanApiContractEvidence.domainTokens(evidence);
                  return PlanApiContractEvidence.overlap(ownerTokens, consumerTokens) > 0;
                })
            .toList();
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  private static boolean declaresTopLevelCollectionRead(PlannedTask task) {
    return extract(task).stream()
        .anyMatch(
            endpoint ->
                endpoint.method().equals("GET")
                    && pathParameterCount(endpoint.path()) == 0
                    && !endpoint.path().equals("/"));
  }

  private static boolean hasDistinctCancelReleaseAction(
      List<ApiEvidence> producers, ApiEvidence consumer) {
    String action = terminalResource(consumer.path());
    if (!Set.of("cancel", "release").contains(action)) return false;
    String alias = action.equals("cancel") ? "release" : "cancel";
    String aliasPath =
        consumer.path().substring(0, consumer.path().length() - action.length()) + alias;
    return producers.stream()
        .anyMatch(
            producer ->
                producer.path().equals(aliasPath)
                    && Set.of("POST", "PUT", "PATCH", "DELETE").contains(producer.method()));
  }

  private static boolean stronglyOwnsRoute(PlannedTask task, ApiEvidence consumer) {
    String normalizedConsumerPath = consumer.path();
    List<ApiEvidence> taskEvidence = extract(task);
    String consumerRouteFamily = routeFamily(normalizedConsumerPath);
    boolean declaresConsumerFamily =
        taskEvidence.stream()
            .map(ApiEvidence::path)
            .map(PlanApiProducerContractReconciler::routeFamily)
            .anyMatch(consumerRouteFamily::equals);
    if (ownsConcreteRouteArtifact(task, routeResource(normalizedConsumerPath))
        && (taskEvidence.isEmpty() || declaresConsumerFamily)) return true;
    String prefix = derivedRouterPrefix(task);
    if (prefix != null && prefixConsistentWithDeclaredRoutes(prefix, taskEvidence)) {
      String normalizedPrefix = normalizedPath(prefix);
      if (normalizedConsumerPath.equals(normalizedPrefix)
          || normalizedConsumerPath.startsWith(normalizedPrefix + "/")) return true;
    }
    if (consumer.method().equals("GET")
        && taskEvidence.stream()
            .map(ApiEvidence::path)
            .map(PlanApiProducerContractReconciler::routeFamily)
            .anyMatch(consumerRouteFamily::equals)) return true;
    return declaresExactPath(task, normalizedConsumerPath);
  }

  private static boolean ownsConcreteRouteArtifact(PlannedTask task, String resource) {
    if (resource.isBlank() || task.touchesFiles() == null) return false;
    return task.touchesFiles().stream()
        .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
        .anyMatch(
            path ->
                path.matches(
                    "(?s).*/(?:api|routers|routes|controllers)/"
                        + Pattern.quote(resource.replace('-', '_'))
                        + "\\.[a-z0-9]+$"));
  }

  private static boolean weaklyOwnsRoute(PlannedTask task, ApiEvidence consumer) {
    String normalizedConsumerPath = consumer.path();
    // An aggregate read is sometimes allocated to the same implementation task as a nested read
    // (for example /resources/{id}/events and /events). A shared terminal resource noun is enough
    // to select a candidate only for GET, and the caller still requires exactly one owner. This
    // must never infer a mutating operation or choose between multiple resource owners.
    if (consumer.method().equals("GET")) {
      String terminalResource = terminalResource(normalizedConsumerPath);
      boolean ownsTerminalRead =
          extract(task).stream()
              .filter(endpoint -> endpoint.method().equals("GET"))
              .map(ApiEvidence::path)
              .map(PlanApiProducerContractReconciler::terminalResource)
              .anyMatch(terminalResource::equals);
      if (!terminalResource.isBlank() && ownsTerminalRead) return true;
    }
    // Service-oriented plans sometimes allocate a resource implementation without a router file.
    // The resource noun in the title/file is a stronger ownership signal than an incidental route
    // mention in another task. The outer caller requires exactly one match before binding.
    Set<String> ownerTokens =
        PlanApiContractEvidence.domainTokens(
            safe(task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()));
    Set<String> familyTokens =
        PlanApiContractEvidence.domainTokens(routeFamily(normalizedConsumerPath));
    return consumer.method().equals("GET")
        && !familyTokens.isEmpty()
        && PlanApiContractEvidence.overlap(ownerTokens, familyTokens) == familyTokens.size();
  }

  private static boolean stronglyOwnsStructuralReadRoute(PlannedTask task, ApiEvidence consumer) {
    if (!consumer.method().equals("GET")) return false;
    String consumerPath = consumer.path();
    String prefix = derivedRouterPrefix(task);
    List<ApiEvidence> reads =
        extract(task).stream().filter(endpoint -> endpoint.method().equals("GET")).toList();
    if (prefix != null && prefixConsistentWithDeclaredRoutes(prefix, reads)) {
      String normalizedPrefix = normalizedPath(prefix);
      if (consumerPath.equals(normalizedPrefix) || consumerPath.startsWith(normalizedPrefix + "/"))
        return true;
    }
    String family = routeFamily(consumerPath);
    if (pathParameterCount(consumerPath) <= 1
        && reads.stream()
            .map(ApiEvidence::path)
            .map(PlanApiProducerContractReconciler::routeFamily)
            .anyMatch(family::equals)) return true;
    return false;
  }

  private static boolean weaklyOwnsStructuralReadRoute(PlannedTask task, ApiEvidence consumer) {
    if (!consumer.method().equals("GET")) return false;
    String consumerPath = consumer.path();
    List<ApiEvidence> reads =
        extract(task).stream().filter(endpoint -> endpoint.method().equals("GET")).toList();
    String terminal = terminalResource(consumerPath);
    if (pathParameterCount(consumerPath) == 0
        && !terminal.isBlank()
        && reads.stream()
            .map(ApiEvidence::path)
            .map(PlanApiProducerContractReconciler::terminalResource)
            .anyMatch(terminal::equals)) return true;
    if (pathParameterCount(consumerPath) == 0) return false;
    Set<String> ownerTokens =
        PlanApiContractEvidence.domainTokens(
            safe(task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()));
    Set<String> familyTokens = PlanApiContractEvidence.domainTokens(routeFamily(consumerPath));
    return !familyTokens.isEmpty()
        && PlanApiContractEvidence.overlap(ownerTokens, familyTokens) == familyTokens.size();
  }

  private static boolean declaresExactPath(PlannedTask task, String normalizedConsumerPath) {
    // A generated backend task may carry the authoritative public path without restating the
    // method (for example "exact paths: /api/adjustments, /api/transfers"). Bind the consumer's
    // method only when that exact path is present and this is the unique implementation owner.
    String evidence =
        safe(task.title())
            + "\n"
            + safe(task.description())
            + "\n"
            + String.join(
                "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
    return PlanSafetyEvidenceSupport.extractApiEndpoints(evidence).stream()
        .map(PlanApiContractEvidence::normalizedPath)
        .anyMatch(normalizedConsumerPath::equals);
  }

  private static boolean prefixConsistentWithDeclaredRoutes(
      String prefix, List<ApiEvidence> evidence) {
    if (evidence.isEmpty()) return true;
    String prefixFamily = routeFamily(normalizedPath(prefix));
    return evidence.stream()
        .map(ApiEvidence::path)
        .map(PlanApiProducerContractReconciler::routeFamily)
        .anyMatch(prefixFamily::equals);
  }

  private static int pathParameterCount(String path) {
    int count = 0;
    int offset = 0;
    String normalized = normalizedPath(path);
    while ((offset = normalized.indexOf("{id}", offset)) >= 0) {
      count++;
      offset += 4;
    }
    return count;
  }

  private static String routeFamily(String path) {
    String normalized = normalizedPath(path);
    int parameter = normalized.indexOf("/{");
    return parameter < 0 ? normalized : normalized.substring(0, parameter);
  }

  private static String terminalResource(String path) {
    String[] segments = normalizedPath(path).split("/");
    for (int index = segments.length - 1; index >= 0; index--) {
      String segment = segments[index];
      if (!segment.isBlank() && !segment.startsWith("{")) return segment;
    }
    return "";
  }

  private static List<ApiEvidence> extractConsumerEvidence(PlannedTask task) {
    List<String> explicitConstraints =
        (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
            .stream().filter(value -> !safe(value).startsWith("API contract binding:")).toList();
    return extract(copyTask(task, safe(task.description()), explicitConstraints));
  }

  private static List<String> bindProducerContract(
      List<String> existing, Set<String> operations, String binding) {
    List<String> constraints = new ArrayList<>(existing == null ? List.<String>of() : existing);
    List<String> paths =
        operations.stream()
            .map(operation -> operation.substring(operation.indexOf(' ') + 1))
            .toList();
    for (int index = 0; index < constraints.size(); index++) {
      String constraint = safe(constraints.get(index));
      if (!paths.stream().allMatch(constraint::contains)) continue;
      if (!constraint.contains(binding)) constraints.set(index, constraint + " " + binding);
      return constraints;
    }
    if (!constraints.contains(binding)) constraints.add(binding);
    return constraints;
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
}
