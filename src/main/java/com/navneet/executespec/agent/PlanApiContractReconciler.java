package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.PARAM_BLOCK;
import static com.navneet.executespec.agent.PlanApiContractEvidence.actionCompatibleProducer;
import static com.navneet.executespec.agent.PlanApiContractEvidence.derivedRouterPrefix;
import static com.navneet.executespec.agent.PlanApiContractEvidence.displayPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.domainTokens;
import static com.navneet.executespec.agent.PlanApiContractEvidence.extract;
import static com.navneet.executespec.agent.PlanApiContractEvidence.hasExplicitActionInvocation;
import static com.navneet.executespec.agent.PlanApiContractEvidence.isImplementationProducerTask;
import static com.navneet.executespec.agent.PlanApiContractEvidence.mergeEvidence;
import static com.navneet.executespec.agent.PlanApiContractEvidence.normalizedPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.overlap;
import static com.navneet.executespec.agent.PlanApiContractEvidence.producerOwnershipScore;
import static com.navneet.executespec.agent.PlanApiContractEvidence.related;
import static com.navneet.executespec.agent.PlanApiContractEvidence.soleSamePathUpdateProducer;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

final class PlanApiContractReconciler {
  private static final Pattern GET_MUTATION_ACTION =
      Pattern.compile(
          "(?i)\\bGET\\s*(?=/)(/[^\\s`\"']*/(?:reserve|commit|cancel|release|deactivate|adjust|transfer))"
              + "(?=\\b|[?`])");
  private static final Pattern GET_WITH_CREATED_RESPONSE =
      Pattern.compile(
          "(?i)\\bGET\\s*(?=/)(/[^\\s`\"']+)(?=[^.\\n]{0,180}(?:\\b201\\b|"
              + "\\brequest\\s+body\\b))");
  private static final Pattern MALFORMED_ANGLE_QUERY_ROUTE =
      Pattern.compile(
          "(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(`?)(/[^\\s`\"'<>?&]+)"
              + "<[^>]+>((?:&[a-zA-Z_][a-zA-Z0-9_]*=<[^>]+>)+)");

  private PlanApiContractReconciler() {}

  static Plan normalizeConsumerHttpSemantics(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null || (!isFrontendOnlyTask(task) && !isTestOnlyTask(task))) {
        tasks.add(task);
        continue;
      }
      String description = normalizeImpossibleGet(task.description());
      List<String> constraints =
          (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
              .stream().map(PlanApiContractReconciler::normalizeImpossibleGet).toList();
      tasks.add(copyTask(task, description, constraints));
    }
    return copyPlan(plan, tasks);
  }

  /**
   * Repairs the legacy {@code /route<int>&page=<int>} spelling only when one backend producer owns
   * the same HTTP method and route identity.
   *
   * <p>The first anonymous angle token cannot express a named path or query parameter, so it is
   * discarded. Named {@code &key=<type>} suffixes become an ordinary query string. Ambiguous or
   * missing producers are deliberately left untouched for {@link PlanApiSyntaxGate} to reject.
   */
  static Plan normalizeMalformedConsumerRoutes(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    List<ApiEvidence> producers =
        mergeEvidence(
            plan.tasks().stream()
                .filter(java.util.Objects::nonNull)
                .filter(task -> !isFrontendOnlyTask(task) && !isTestOnlyTask(task))
                .filter(PlanApiContractEvidence::isImplementationProducerTask)
                .flatMap(task -> extract(task).stream())
                .toList());
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null || (!isFrontendOnlyTask(task) && !isTestOnlyTask(task))) {
        tasks.add(task);
        continue;
      }
      String description = normalizeMalformedConsumerRoutes(task.description(), producers);
      List<String> constraints =
          (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
              .stream().map(value -> normalizeMalformedConsumerRoutes(value, producers)).toList();
      tasks.add(copyTask(task, description, constraints));
    }
    return copyPlan(plan, tasks);
  }

  private static String normalizeMalformedConsumerRoutes(
      String evidence, List<ApiEvidence> producers) {
    String source = safe(evidence);
    Matcher matcher = MALFORMED_ANGLE_QUERY_ROUTE.matcher(source);
    StringBuffer normalized = new StringBuffer(source.length());
    while (matcher.find()) {
      String method = matcher.group(1).toUpperCase(Locale.ROOT);
      String route = matcher.group(3);
      String identity = normalizedPath(route);
      List<ApiEvidence> candidates =
          producers.stream()
              .filter(producer -> producer.method().equals(method))
              .filter(producer -> producer.path().equals(identity))
              .toList();
      if (candidates.size() != 1) {
        matcher.appendReplacement(normalized, Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      String query = "?" + matcher.group(4).substring(1);
      String replacement =
          method + " " + matcher.group(2) + authoritativeDisplayPath(candidates.getFirst()) + query;
      matcher.appendReplacement(normalized, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(normalized);
    return normalized.toString();
  }

  private static String normalizeImpossibleGet(String evidence) {
    String normalized = safe(evidence);
    normalized =
        GET_MUTATION_ACTION.matcher(normalized).replaceAll(Matcher.quoteReplacement("POST") + "$1");
    return GET_WITH_CREATED_RESPONSE
        .matcher(normalized)
        .replaceAll(Matcher.quoteReplacement("POST") + "$1");
  }

  static void addViolations(Plan plan, List<String> violations) {
    List<ApiEvidence> producers = new ArrayList<>();
    List<ApiEvidence> consumers = new ArrayList<>();
    java.util.Map<String, String> ownedRoutePrefixes = ownedRoutePrefixes(plan.tasks());
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      if (isFrontendOnlyTask(task) || isTestOnlyTask(task)) {
        consumers.addAll(extractConsumerEvidence(task));
      } else if (isImplementationProducerTask(task)) {
        producers.addAll(extract(task));
      }
    }
    producers = mergeEvidence(producers);
    consumers = mergeEvidence(consumers);
    for (ApiEvidence consumer : consumers) {
      if (consumer.path().isBlank()) continue;
      if (PlanApiFrameworkRouteSupport.isExplicitFrameworkRoute(plan, consumer)) continue;
      if (exactProducer(producers, consumer, ownedRoutePrefixes) != null) continue;
      ApiEvidence likelyProducer =
          producers.stream()
              .filter(producer -> producer.method().equals(consumer.method()))
              .filter(producer -> related(producer, consumer))
              .max(
                  java.util.Comparator.comparingInt(
                      producer -> overlap(producer.domainTokens(), consumer.domainTokens())))
              .orElse(null);
      if (likelyProducer != null) {
        violations.add(
            consumer.taskId()
                + ": API consumer uses "
                + consumer.method()
                + " "
                + displayPath(consumer.path())
                + " but producer "
                + likelyProducer.taskId()
                + " owns "
                + likelyProducer.method()
                + " "
                + displayPath(likelyProducer.path()));
      } else {
        ApiEvidence samePath =
            producers.stream()
                .filter(producer -> producer.path().equals(consumer.path()))
                .findFirst()
                .orElse(null);
        violations.add(
            samePath == null
                ? consumer.taskId()
                    + ": API consumer uses "
                    + consumer.method()
                    + " "
                    + displayPath(consumer.path())
                    + " but no backend task declares that operation"
                : consumer.taskId()
                    + ": API consumer uses "
                    + consumer.method()
                    + " "
                    + displayPath(consumer.path())
                    + " but producer "
                    + samePath.taskId()
                    + " declares "
                    + samePath.method()
                    + " for that path");
      }
    }
    for (ApiEvidence consumer : consumers) {
      ApiEvidence producer = exactProducer(producers, consumer, ownedRoutePrefixes);
      if (producer == null
          || consumer.declaredQueryParams().isEmpty()
          || producer.declaredQueryParams().isEmpty()) continue;
      Set<String> unsupported = new LinkedHashSet<>(consumer.declaredQueryParams());
      unsupported.removeAll(producer.declaredQueryParams());
      if (!unsupported.isEmpty()) {
        violations.add(
            consumer.taskId()
                + ": API consumer query parameters "
                + unsupported
                + " are absent from producer "
                + producer.taskId()
                + "; producer declares "
                + producer.declaredQueryParams());
      }
    }
  }

  static Plan reconcileConsumers(Plan plan) {
    return reconcileConsumers(plan, true);
  }

  /**
   * Repairs consumer methods and paths before requirement-backed query reconciliation.
   *
   * <p>Query alignment is deliberately deferred. Otherwise a consumer whose route still points at
   * the wrong producer can either promote its filters onto that producer or lose authoritative
   * requirement-backed filters before the route is corrected.
   */
  static Plan reconcileConsumerOperations(Plan plan) {
    return reconcileConsumers(plan, false);
  }

  private static Plan reconcileConsumers(Plan plan, boolean alignQueries) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    List<ApiEvidence> producers =
        mergeEvidence(
            plan.tasks().stream()
                .filter(java.util.Objects::nonNull)
                .filter(task -> !isFrontendOnlyTask(task) && !isTestOnlyTask(task))
                .filter(PlanApiContractEvidence::isImplementationProducerTask)
                .flatMap(task -> extract(task).stream())
                .toList());
    java.util.Map<String, String> ownedRoutePrefixes = new java.util.LinkedHashMap<>();
    ownedRoutePrefixes.putAll(ownedRoutePrefixes(plan.tasks()));
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null || (!isFrontendOnlyTask(task) && !isTestOnlyTask(task))) {
        tasks.add(task);
        continue;
      }
      String description = safe(task.description());
      List<String> constraints =
          (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
              .stream()
                  .filter(value -> !safe(value).startsWith("API contract binding:"))
                  .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      List<String> bindings = new ArrayList<>();
      PlanApiQueryAligner.Occurrences queryOccurrences = new PlanApiQueryAligner.Occurrences();
      for (ApiEvidence consumer : extractConsumerEvidence(task)) {
        String canonicalApiPath =
            PlanApiFrameworkRouteSupport.canonicalizePrefixedRoute(plan, consumer);
        if (canonicalApiPath == null) canonicalApiPath = canonicalApiPath(producers, consumer);
        if (canonicalApiPath != null) {
          String replacement = consumer.method() + " " + canonicalApiPath;
          description = replaceConsumerOperation(description, consumer, replacement);
          Pattern literalOperation = literalOperation(consumer);
          constraints =
              constraints.stream()
                  .map(
                      constraint ->
                          literalOperation
                              .matcher(constraint)
                              .replaceFirst(Matcher.quoteReplacement(replacement)))
                  .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        ApiEvidence producer =
            PlanApiReadIntentResolver.resolveRouteSpecific(producers, task, consumer);
        boolean readIntentSelected = producer != null;
        if (producer == null)
          producer =
              PlanApiReadIntentResolver.resolveContextSpecific(
                  producers, consumer, ownedRoutePrefixes);
        if (producer != null) readIntentSelected = true;
        if (producer == null)
          producer = PlanApiTypedClientActionResolver.resolve(producers, consumer, task);
        if (producer == null) producer = actionCompatibleProducer(producers, consumer);
        if (producer == null) producer = soleSamePathUpdateProducer(producers, consumer);
        if (producer == null) producer = exactProducer(producers, consumer, ownedRoutePrefixes);
        if (producer == null) {
          producer = PlanApiReadIntentResolver.resolve(producers, task);
          readIntentSelected = producer != null;
        }
        if (producer == null && !hasExplicitActionInvocation(consumer)) {
          producer =
              producers.stream()
                  .filter(candidate -> candidate.method().equals(consumer.method()))
                  .filter(
                      candidate ->
                          candidate.path().equals(consumer.path()) || related(candidate, consumer))
                  .max(
                      java.util.Comparator.comparingInt(
                              (ApiEvidence candidate) ->
                                  producerOwnershipScore(candidate, consumer, ownedRoutePrefixes))
                          .thenComparingInt(
                              candidate ->
                                  overlap(candidate.domainTokens(), consumer.domainTokens())))
                  .orElse(null);
        }
        if (producer == null) {
          String composition = aggregateComposition(producers, consumer, task);
          if (composition != null) {
            Pattern literalOperation = literalOperation(consumer);
            description = neutralizeAggregateParams(description, literalOperation);
            description = replaceConsumerOperation(description, consumer, composition);
            constraints =
                constraints.stream()
                    .map(
                        constraint -> {
                          String withoutAggregateParams =
                              neutralizeAggregateParams(constraint, literalOperation);
                          return literalOperation
                              .matcher(withoutAggregateParams)
                              .replaceAll(Matcher.quoteReplacement(composition));
                        })
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            continue;
          }
          List<ApiEvidence> samePath =
              producers.stream()
                  .filter(candidate -> candidate.path().equals(consumer.path()))
                  .toList();
          if (samePath.size() == 1) producer = samePath.getFirst();
        }
        if (producer == null) continue;
        if (!producer.method().equals(consumer.method())
            || !producer.path().equals(consumer.path())) {
          boolean preserveMutationMethod =
              !readIntentSelected
                  && producer.method().equals("GET")
                  && PlanApiReadIntentResolver.isMutationMethod(consumer.method())
                  && !PlanApiReadIntentResolver.hasReadIntent(consumer);
          String replacementMethod = preserveMutationMethod ? consumer.method() : producer.method();
          String replacement = replacementMethod + " " + producer.rawPath();
          Pattern literalOperation = literalOperation(consumer);
          description = replaceConsumerOperation(description, consumer, replacement);
          constraints =
              constraints.stream()
                  .map(
                      constraint ->
                          literalOperation
                              .matcher(constraint)
                              .replaceAll(Matcher.quoteReplacement(replacement)))
                  .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        if (alignQueries
            && !producer.declaredQueryParams().isEmpty()
            && !consumer.declaredQueryParams().isEmpty()
            && !producer.declaredQueryParams().containsAll(consumer.declaredQueryParams())) {
          int occurrenceIndex = queryOccurrences.next(consumer, producer);
          description = PlanApiQueryAligner.align(description, consumer, producer, occurrenceIndex);
          ApiEvidence matchedProducer = producer;
          constraints =
              constraints.stream()
                  .map(value -> PlanApiQueryAligner.align(value, consumer, matchedProducer))
                  .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        if (producer.method().equals(consumer.method())) {
          bindings.add(
              "API contract binding: "
                  + producer.taskId()
                  + " is authoritative for "
                  + producer.method()
                  + " "
                  + authoritativeDisplayPath(producer)
                  + "; use query parameters "
                  + producer.declaredQueryParams()
                  + (producer.responseFields().isEmpty()
                      ? ""
                      : " and response fields " + producer.responseFields())
                  + "; do not invent aliases or undeclared response fields.");
        }
      }
      for (String binding : bindings) if (!constraints.contains(binding)) constraints.add(binding);
      tasks.add(copyTask(task, description, constraints));
    }
    return copyPlan(plan, tasks);
  }

  static Plan reconcileRequirementBackedProducerQueries(Plan plan) {
    return PlanApiRequirementQueryPromoter.reconcile(plan);
  }

  private static String aggregateComposition(
      List<ApiEvidence> producers, ApiEvidence consumer, PlannedTask task) {
    if (!consumer.method().equals("GET")) return null;
    String aggregateEvidence =
        (safe(task.title()) + " " + consumer.path() + " " + consumer.context())
            .toLowerCase(Locale.ROOT);
    if (!containsAny(aggregateEvidence, "dashboard", "summary", "stats", "metrics", "overview"))
      return null;
    List<ApiEvidence> collectionReads =
        producers.stream()
            .filter(producer -> producer.method().equals("GET"))
            .filter(producer -> !producer.path().contains("{id}"))
            .filter(producer -> !producer.path().equals("/"))
            .filter(
                producer ->
                    !producer.path().matches(".*/(?:health|openapi(?:\\.json)?|docs)(?:/.*)?$"))
            .toList();
    Set<String> aggregateTokens = domainTokens(aggregateEvidence);
    List<ApiEvidence> relatedReads =
        collectionReads.stream()
            .filter(
                producer ->
                    overlap(producer.domainTokens(), aggregateTokens) > 0
                        || producer.responseFields().stream()
                            .map(PlanApiContractEvidence::singular)
                            .anyMatch(aggregateTokens::contains))
            .toList();
    List<String> reads =
        (relatedReads.isEmpty() ? collectionReads : relatedReads)
            .stream()
                .map(producer -> "GET " + displayPath(producer.path()))
                .distinct()
                .limit(8)
                .toList();
    return reads.isEmpty() ? null : "compose client-side from " + String.join(", ", reads);
  }

  private static String neutralizeAggregateParams(String evidence, Pattern literalOperation) {
    Matcher operation = literalOperation.matcher(evidence);
    if (!operation.find()) return evidence;
    int lineStart = evidence.lastIndexOf('\n', operation.start());
    int scopedStart = lineStart < 0 ? 0 : lineStart + 1;
    String beforeOperation = evidence.substring(scopedStart, operation.start());
    Matcher params = PARAM_BLOCK.matcher(beforeOperation);
    if (!params.find()) return evidence;
    String neutralized = params.replaceFirst(Matcher.quoteReplacement("params?: {}"));
    return evidence.substring(0, scopedStart) + neutralized + evidence.substring(operation.start());
  }

  private static Pattern literalOperation(ApiEvidence consumer) {
    return Pattern.compile(
        "(?i)\\b"
            + Pattern.quote(consumer.method())
            + "\\s*"
            + Pattern.quote(consumer.rawPath())
            + "(?![/a-z0-9_{}:-])");
  }

  /**
   * The same method/path can legitimately appear more than once with different semantics (for
   * example collection GET/list and POST/create). Rewrite only the extracted operation context;
   * falling back to the path-bounded pattern is safe for older evidence without recoverable
   * context.
   */
  private static String replaceConsumerOperation(
      String evidence, ApiEvidence consumer, String replacement) {
    String source = safe(evidence);
    String context = safe(consumer.context());
    int contextStart = source.toLowerCase(Locale.ROOT).indexOf(context.toLowerCase(Locale.ROOT));
    if (contextStart >= 0) {
      int contextEnd = Math.min(source.length(), contextStart + context.length());
      String scoped = source.substring(contextStart, contextEnd);
      String replaced =
          literalOperation(consumer)
              .matcher(scoped)
              .replaceFirst(Matcher.quoteReplacement(replacement));
      if (!scoped.equals(replaced)) {
        return source.substring(0, contextStart) + replaced + source.substring(contextEnd);
      }
    }
    return literalOperation(consumer)
        .matcher(source)
        .replaceFirst(Matcher.quoteReplacement(replacement));
  }

  private static java.util.Map<String, String> ownedRoutePrefixes(List<PlannedTask> tasks) {
    java.util.Map<String, String> prefixes = new java.util.LinkedHashMap<>();
    for (PlannedTask candidate : tasks) {
      if (candidate == null) continue;
      String prefix = derivedRouterPrefix(candidate);
      if (prefix != null) prefixes.put(candidate.id(), normalizedPath(prefix));
    }
    return prefixes;
  }

  private static ApiEvidence exactProducer(
      List<ApiEvidence> producers,
      ApiEvidence consumer,
      java.util.Map<String, String> ownedRoutePrefixes) {
    return producers.stream()
        .filter(candidate -> candidate.method().equals(consumer.method()))
        .filter(candidate -> candidate.path().equals(consumer.path()))
        .max(
            java.util.Comparator.comparingInt(
                candidate -> producerOwnershipScore(candidate, consumer, ownedRoutePrefixes)))
        .orElse(null);
  }

  private static List<ApiEvidence> extractConsumerEvidence(PlannedTask task) {
    List<String> sourceConstraints =
        task.hardConstraints() == null ? List.of() : task.hardConstraints();
    List<String> explicitConstraints =
        sourceConstraints.stream()
            .filter(value -> !safe(value).startsWith("API contract binding:"))
            .toList();
    return extract(copyTask(task, safe(task.description()), explicitConstraints));
  }

  private static String canonicalApiPath(List<ApiEvidence> producers, ApiEvidence consumer) {
    if (consumer.rawPath().startsWith("/api")
        || !consumer.rawPath().startsWith("/")
        || consumer.path().equals("/")) return null;
    boolean hasApiProducerPrefix =
        producers.stream()
            .filter(producer -> producer.rawPath().startsWith("/api"))
            .map(ApiEvidence::path)
            .anyMatch(
                producerPath ->
                    consumer.path().equals(producerPath)
                        || consumer.path().startsWith(producerPath + "/"));
    return hasApiProducerPrefix ? displayPath(consumer.path()) : null;
  }

  private static String authoritativeDisplayPath(ApiEvidence producer) {
    String raw = safe(producer.rawPath());
    int query = raw.indexOf('?');
    if (query >= 0) raw = raw.substring(0, query);
    return raw.startsWith("/api") ? raw : displayPath(producer.path());
  }

  private static boolean containsAny(String value, String... needles) {
    for (String needle : needles) if (value.contains(needle)) return true;
    return false;
  }

  private static PlannedTask copyTask(
      PlannedTask source, String description, List<String> constraints) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(description)
        .complexity(source.complexity())
        .touchesFiles(source.touchesFiles())
        .requiredArtifacts(source.requiredArtifacts())
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
