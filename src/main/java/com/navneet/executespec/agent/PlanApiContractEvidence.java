package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts and compares API operations from generated task evidence. */
final class PlanApiContractEvidence {
  private static final Pattern ENDPOINT =
      Pattern.compile(
          "(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s*(?:/|`/)"
              + "(?!(?:GET|POST|PUT|PATCH|DELETE)(?:\\b|/))([a-z0-9_{}?=&./:-]*)");
  private static final Pattern COMPACT_METHOD_LIST =
      Pattern.compile(
          "(?i)\\b((?:GET|POST|PUT|PATCH|DELETE)(?:\\s*/\\s*"
              + "(?:GET|POST|PUT|PATCH|DELETE))+)"
              + "\\s+`?(/(?:api(?:/|\\b))?[a-z0-9_{}?=&./:-]*)");
  private static final Pattern ROUTER_PREFIX =
      Pattern.compile(
          "(?i)(?:router\\s+)?prefix\\s*(?:=|:)?\\s*[\"'`]?(/api(?:/[a-z0-9_{}./:-]+)?)");
  private static final Pattern SECTION_ROUTER_PREFIX =
      Pattern.compile(
          "(?im)(?:\\*\\*[^*\\n]{1,80}\\b(?:endpoints?|routes?)\\b\\*\\*|"
              + "(?:^|\\n)\\s*(?:#{1,6}\\s*)?[^:\\n]{1,80}\\b(?:endpoints?|routes?)\\b)"
              + "\\s*\\(\\s*[\"'`]?(/api(?:/[a-z0-9_{}./:-]+)?)[\"'`]?\\s*\\)\\s*:");
  private static final Pattern MARKDOWN_SECTION_BOUNDARY =
      Pattern.compile("(?m)^\\s*(?:#{1,6}\\s+\\S.*|\\*\\*[^*\\n]{1,120}\\*\\*\\s*:?)\\s*$");
  private static final Pattern QUERY_PARAM = Pattern.compile("[?&]([a-zA-Z_][a-zA-Z0-9_]*)");
  private static final Pattern NAMED_QUERY_PARAM_LIST =
      Pattern.compile(
          "(?i)\\bquery\\s+parameters?\\s*(?:are|include|includes|:)?\\s*"
              + "([a-zA-Z_][a-zA-Z0-9_]*(?:(?:\\s*,\\s*(?:and\\s+)?|\\s*/\\s*|\\s+and\\s+)"
              + "[a-zA-Z_][a-zA-Z0-9_]*){1,12})");
  private static final Pattern SUPPORTED_QUERY_PARAM_LIST =
      Pattern.compile(
          "(?i)\\bsupports?\\s+"
              + "([a-zA-Z_][a-zA-Z0-9_]*(?:(?:\\s*,\\s*(?:and\\s+)?|\\s*/\\s*|\\s+and\\s+)"
              + "[a-zA-Z_][a-zA-Z0-9_]*){1,12})\\s+query\\s+parameters?\\b");
  static final Pattern PARAM_BLOCK = Pattern.compile("(?i)params?\\??\\s*:\\s*\\{([^}]+)}");
  private static final Pattern IDENTIFIER = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\??");
  private static final Set<String> DOMAIN_STOP_WORDS =
      Set.of(
          "api",
          "get",
          "post",
          "put",
          "patch",
          "delete",
          "list",
          "create",
          "update",
          "remove",
          "build",
          "implement",
          "screen",
          "page",
          "endpoint",
          "route",
          "frontend",
          "backend",
          "history",
          "details",
          "detail",
          "with",
          "and",
          "the");

  private PlanApiContractEvidence() {}

  static List<ApiEvidence> extract(PlannedTask task) {
    String evidence =
        safe(task.title())
            + "\n"
            + safe(task.description())
            + "\n"
            + String.join(
                "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
    List<RoutePrefix> prefixes = routePrefixes(task, evidence);
    List<ApiEvidence> endpoints = new ArrayList<>();
    if (!PlanSafetyEvidenceSupport.isFrontendOnlyTask(task)
        && !PlanSafetyEvidenceSupport.isTestOnlyTask(task)) {
      Matcher compactMethods = COMPACT_METHOD_LIST.matcher(evidence);
      while (compactMethods.find()) {
        String rawPath = compactMethods.group(2).replaceAll("[`'\".,;:]+$", "");
        rawPath = applyNearestRouterPrefix(rawPath, compactMethods.start(), prefixes);
        String path = normalizedPath(rawPath);
        Set<String> tokens = new LinkedHashSet<>(domainTokens(path));
        String context = operationContext(evidence, compactMethods.start(), compactMethods.end());
        for (String methodToken : compactMethods.group(1).split("\\s*/\\s*")) {
          endpoints.add(
              new ApiEvidence(
                  task.id(),
                  methodToken.toUpperCase(Locale.ROOT),
                  rawPath,
                  path,
                  Set.copyOf(tokens),
                  queryParams(context),
                  ApiResponseContractExtractor.responseFields(evidence, Set.copyOf(tokens)),
                  context));
        }
      }
    }
    Matcher matcher = ENDPOINT.matcher(evidence);
    while (matcher.find()) {
      String methodToken = matcher.group(1);
      if (ApiResponseContractExtractor.isCompactLowercaseHelperSyntax(matcher)) continue;
      String method = methodToken.toUpperCase(Locale.ROOT);
      String rawPath = "/" + matcher.group(2).replaceAll("[`'\".,;:]+$", "");
      rawPath += ApiResponseContractExtractor.optionalItemSuffix(evidence, matcher.end());
      rawPath = applyNearestRouterPrefix(rawPath, matcher.start(), prefixes);
      String path = normalizedPath(rawPath);
      Set<String> tokens = new LinkedHashSet<>(domainTokens(path));
      String context = operationContext(evidence, matcher.start(), matcher.end());
      endpoints.add(
          new ApiEvidence(
              task.id(),
              method,
              rawPath,
              path,
              Set.copyOf(tokens),
              queryParams(context),
              ApiResponseContractExtractor.responseFields(evidence, Set.copyOf(tokens)),
              context));
    }
    addAnalogousPrefixedEndpoints(evidence, prefixes, endpoints);
    return endpoints;
  }

  static List<ApiEvidence> mergeEvidence(List<ApiEvidence> evidence) {
    java.util.Map<String, ApiEvidence> merged = new java.util.LinkedHashMap<>();
    for (ApiEvidence current : evidence) {
      String key = current.taskId() + "\u0000" + current.method() + "\u0000" + current.path();
      ApiEvidence previous = merged.get(key);
      if (previous == null) {
        merged.put(key, current);
        continue;
      }
      LinkedHashSet<String> queryParams = new LinkedHashSet<>(previous.declaredQueryParams());
      queryParams.addAll(current.declaredQueryParams());
      LinkedHashSet<String> responseFields = new LinkedHashSet<>(previous.responseFields());
      responseFields.addAll(current.responseFields());
      LinkedHashSet<String> tokens = new LinkedHashSet<>(previous.domainTokens());
      tokens.addAll(current.domainTokens());
      merged.put(
          key,
          new ApiEvidence(
              previous.taskId(),
              previous.method(),
              previous.rawPath(),
              previous.path(),
              Set.copyOf(tokens),
              Set.copyOf(queryParams),
              Set.copyOf(responseFields),
              previous.context() + "\n" + current.context()));
    }
    return List.copyOf(merged.values());
  }

  static String derivedRouterPrefix(PlannedTask task) {
    if (task == null || task.touchesFiles() == null) return null;
    List<String> resources =
        task.touchesFiles().stream()
            .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
            .filter(
                path ->
                    path.matches(".*[/](?:routers|routes)/[a-z0-9_-]+\\.[a-z0-9]+$")
                        || path.matches(
                            "(?:^|.*/)(?:backend|server)/.*[/]api/[a-z0-9_-]+\\.[a-z0-9]+$"))
            .map(path -> path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.')))
            .filter(name -> !Set.of("index", "main", "health", "common", "__init__").contains(name))
            .distinct()
            .toList();
    return resources.size() == 1 ? "/" + resources.getFirst().replace('_', '-') : null;
  }

  /**
   * Returns whether endpoint mentions in this task are implementation evidence.
   *
   * <p>Cross-cutting bootstrap tasks commonly repeat every route while configuring CORS, global
   * errors, or OpenAPI metadata. Treating those references as producers makes the metadata task
   * steal ownership from the actual resource implementation. A real router/controller file is still
   * authoritative even when its title also mentions documentation.
   */
  static boolean isImplementationProducerTask(PlannedTask task) {
    if (task == null) return false;
    if (derivedRouterPrefix(task) != null || ownsResourceRouteArtifact(task)) return true;
    String identity =
        (safe(task.title())
                + "\n"
                + String.join("\n", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    boolean crossCuttingMetadata =
        identity.matches(
            "(?s).*(?:\\bcors\\b|global\\s+error|error\\s+handling|openapi\\s+metadata|"
                + "api\\s+metadata|api\\s+documentation).*");
    return !crossCuttingMetadata;
  }

  /**
   * Detects concrete resource route/controller artifacts even when one task owns several of them.
   *
   * <p>{@link #derivedRouterPrefix(PlannedTask)} deliberately returns a prefix only for a single
   * resource file. That ambiguity is useful for composing relative paths, but it must not make a
   * multi-router implementation task look like metadata merely because the same task also owns CORS
   * or sanitized-error wiring.
   */
  private static boolean ownsResourceRouteArtifact(PlannedTask task) {
    if (task.touchesFiles() == null) return false;
    return task.touchesFiles().stream()
        .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
        .anyMatch(
            path -> {
              boolean routeModule =
                  path.matches(".*[/](?:routers|routes|controllers)/[a-z0-9_-]+\\.[a-z0-9]+$")
                      || path.matches(
                          "(?:^|.*/)(?:backend|server)/.*[/]api/[a-z0-9_-]+\\.[a-z0-9]+$");
              if (!routeModule) return false;
              String fileName = path.substring(path.lastIndexOf('/') + 1);
              String resource = fileName.substring(0, fileName.lastIndexOf('.'));
              return !Set.of(
                      "index",
                      "main",
                      "health",
                      "common",
                      "config",
                      "errors",
                      "error_handler",
                      "error_handlers",
                      "__init__")
                  .contains(resource);
            });
  }

  static String normalizedPath(String raw) {
    String path = safe(raw).trim().toLowerCase(Locale.ROOT);
    int query = path.indexOf('?');
    if (query >= 0) path = path.substring(0, query);
    path = path.replaceAll("//+", "/").replaceAll("/+$", "");
    path = path.replaceAll("/:([a-z_][a-z0-9_]*)", "/{$1}");
    path = path.replaceAll("\\{[a-z_][a-z0-9_]*}", "{id}");
    if (path.startsWith("/api/")) path = path.substring(4);
    else if (path.equals("/api")) path = "/";
    return path.isBlank() ? "/" : path;
  }

  static String displayPath(String normalized) {
    return normalized.isBlank() || normalized.equals("/") ? "/api" : "/api" + normalized;
  }

  /** Canonical route identity that deliberately retains the public namespace. */
  static String canonicalIdentityPath(String raw) {
    String path = safe(raw).trim().toLowerCase(Locale.ROOT);
    int query = path.indexOf('?');
    if (query >= 0) path = path.substring(0, query);
    path = path.replaceAll("//+", "/").replaceAll("/+$", "");
    path = path.replaceAll("/:([a-z_][a-z0-9_]*)", "/{$1}");
    path = path.replaceAll("\\{[a-z_][a-z0-9_]*}", "{id}");
    if (!path.startsWith("/")) path = "/" + path;
    return path.isBlank() ? "/" : path;
  }

  static boolean related(ApiEvidence left, ApiEvidence right) {
    if (left.path().equals(right.path())) return true;
    int shared = overlap(left.domainTokens(), right.domainTokens());
    return shared > 0
        && (lastSegment(left.path()).contains(lastSegment(right.path()))
            || lastSegment(right.path()).contains(lastSegment(left.path()))
            || shared >= 2);
  }

  static int overlap(Set<String> left, Set<String> right) {
    int count = 0;
    for (String token : left) if (right.contains(token)) count++;
    return count;
  }

  static Set<String> domainTokens(String value) {
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    for (String token : safe(value).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (token.length() < 3 || DOMAIN_STOP_WORDS.contains(token) || token.matches("t\\d+"))
        continue;
      tokens.add(singular(token));
    }
    return Set.copyOf(tokens);
  }

  static String singular(String value) {
    if (value.endsWith("ies") && value.length() > 4)
      return value.substring(0, value.length() - 3) + "y";
    if (value.endsWith("s") && !value.endsWith("ss") && value.length() > 3) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  static int producerOwnershipScore(
      ApiEvidence producer,
      ApiEvidence consumer,
      java.util.Map<String, String> ownedRoutePrefixes) {
    int score = producer.path().equals(consumer.path()) ? 100 : 0;
    String ownedPrefix = ownedRoutePrefixes.get(producer.taskId());
    if (ownedPrefix != null
        && (producer.path().equals(ownedPrefix) || producer.path().startsWith(ownedPrefix + "/"))) {
      score += 1_000;
    }
    return score;
  }

  static ApiEvidence actionCompatibleProducer(List<ApiEvidence> producers, ApiEvidence consumer) {
    String context = consumer.context();
    final String desiredMethod;
    String explicitAction =
        List.of("reserve", "commit", "cancel", "release", "deactivate", "adjust", "transfer")
            .stream()
            .filter(action -> isActionInvocation(consumer, context, action))
            .findFirst()
            .orElse(null);
    if (explicitAction != null) {
      List<ApiEvidence> actionCandidates =
          producers.stream()
              .filter(producer -> producer.path().endsWith("/" + explicitAction))
              .filter(
                  producer -> Set.of("POST", "PUT", "PATCH", "DELETE").contains(producer.method()))
              .toList();
      if (actionCandidates.size() == 1) return actionCandidates.getFirst();
    }
    if (consumer.path().endsWith("/deactivate")) {
      desiredMethod = "DELETE";
    } else if ((consumer.method().equals("GET")
            || consumer.method().equals("POST")
            || consumer.method().equals("PUT"))
        && context.matches("(?s).*\\b(?:save|submit|edit|update|crud)\\b.*")) {
      desiredMethod = null;
    } else {
      return null;
    }
    List<ApiEvidence> candidates =
        producers.stream()
            .filter(
                producer ->
                    desiredMethod == null
                        ? producer.method().equals("PATCH") || producer.method().equals("PUT")
                        : producer.method().equals(desiredMethod))
            .filter(producer -> overlap(producer.domainTokens(), consumer.domainTokens()) > 0)
            .filter(
                producer ->
                    producer.path().contains("{id}")
                        && (consumer.path().contains("{id}")
                            || consumer.path().endsWith("/deactivate")
                            || context.contains("crud")))
            .toList();
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  static boolean hasExplicitActionInvocation(ApiEvidence consumer) {
    String context = consumer.context();
    return List.of("reserve", "commit", "cancel", "release", "deactivate", "adjust", "transfer")
        .stream()
        .anyMatch(action -> isActionInvocation(consumer, context, action));
  }

  private static boolean isActionInvocation(ApiEvidence consumer, String context, String action) {
    if (consumer.path().endsWith("/" + action)) return true;
    if (consumer.method().equals("GET")) return false;
    return context.matches("(?s).*\\b" + Pattern.quote(action) + "\\b.*");
  }

  static ApiEvidence soleSamePathUpdateProducer(List<ApiEvidence> producers, ApiEvidence consumer) {
    if (!Set.of("PUT", "PATCH").contains(consumer.method()) || !consumer.path().contains("{id}"))
      return null;
    List<ApiEvidence> candidates =
        producers.stream()
            .filter(producer -> producer.path().equals(consumer.path()))
            .filter(producer -> Set.of("PUT", "PATCH").contains(producer.method()))
            .toList();
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  static String typedParams(Set<String> params) {
    return params.stream()
        .map(
            value ->
                value
                    + "?: "
                    + (value.matches("(?i).*(?:offset|limit|page|size|count)$")
                        ? "number"
                        : "string"))
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private static List<RoutePrefix> routePrefixes(PlannedTask task, String evidence) {
    List<RoutePrefix> prefixes = new ArrayList<>();
    Matcher matcher = ROUTER_PREFIX.matcher(evidence);
    while (matcher.find()) {
      prefixes.add(scopedPrefix(evidence, matcher.start(), matcher.group(1)));
    }
    Matcher sectionMatcher = SECTION_ROUTER_PREFIX.matcher(evidence);
    while (sectionMatcher.find()) {
      prefixes.add(scopedPrefix(evidence, sectionMatcher.start(), sectionMatcher.group(1)));
    }
    prefixes =
        prefixes.stream()
            .distinct()
            .sorted(java.util.Comparator.comparingInt(RoutePrefix::offset))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    if (prefixes.isEmpty()) {
      String derived = derivedRouterPrefix(task);
      if (derived != null) prefixes.add(new RoutePrefix(0, 0, evidence.length(), derived));
    }
    return prefixes;
  }

  private static RoutePrefix scopedPrefix(String evidence, int offset, String path) {
    int sectionStart = 0;
    int sectionEnd = evidence.length();
    Matcher boundary = MARKDOWN_SECTION_BOUNDARY.matcher(evidence);
    while (boundary.find()) {
      if (boundary.start() <= offset) {
        sectionStart = boundary.start();
        continue;
      }
      sectionEnd = boundary.start();
      break;
    }
    return new RoutePrefix(sectionStart, offset, sectionEnd, path.replaceAll("/+$", ""));
  }

  private static void addAnalogousPrefixedEndpoints(
      String evidence, List<RoutePrefix> prefixes, List<ApiEvidence> endpoints) {
    if (prefixes.size() < 2 || !evidence.matches("(?is).*\\bsame\\s+[^.\\n]{0,40}endpoints?\\b.*"))
      return;
    for (int index = 1; index < prefixes.size(); index++) {
      RoutePrefix current = prefixes.get(index);
      String currentPath = normalizedPath(current.path());
      boolean alreadyDeclared =
          endpoints.stream().anyMatch(endpoint -> endpoint.path().startsWith(currentPath));
      if (alreadyDeclared) continue;
      RoutePrefix previous = prefixes.get(index - 1);
      String previousPath = normalizedPath(previous.path());
      List<ApiEvidence> previousEndpoints =
          endpoints.stream().filter(endpoint -> endpoint.path().startsWith(previousPath)).toList();
      for (ApiEvidence source : previousEndpoints) {
        String suffix = source.path().substring(previousPath.length());
        String path = currentPath + suffix;
        Set<String> tokens = domainTokens(path);
        endpoints.add(
            new ApiEvidence(
                source.taskId(),
                source.method(),
                current.path() + suffix,
                path,
                tokens,
                source.declaredQueryParams(),
                ApiResponseContractExtractor.responseFields(evidence, tokens),
                "same endpoints as " + previous.path()));
      }
    }
  }

  private static String applyNearestRouterPrefix(
      String rawPath, int endpointOffset, List<RoutePrefix> prefixes) {
    if (rawPath.startsWith("/api") || prefixes.isEmpty()) return rawPath;
    RoutePrefix nearest = null;
    for (RoutePrefix prefix : prefixes) {
      if (endpointOffset < prefix.sectionStart() || endpointOffset >= prefix.sectionEnd()) continue;
      if (prefix.offset() <= endpointOffset) nearest = prefix;
      else if (nearest == null) nearest = prefix;
    }
    if (nearest == null || nearest.path().equals("/api")) return rawPath;
    return nearest.path() + (rawPath.equals("/") ? "" : rawPath);
  }

  private static Set<String> queryParams(String evidence) {
    LinkedHashSet<String> params = new LinkedHashSet<>();
    Matcher queryMatcher = QUERY_PARAM.matcher(evidence);
    while (queryMatcher.find()) params.add(queryMatcher.group(1));
    addNamedQueryParams(params, NAMED_QUERY_PARAM_LIST.matcher(evidence));
    addNamedQueryParams(params, SUPPORTED_QUERY_PARAM_LIST.matcher(evidence));
    Matcher blockMatcher = PARAM_BLOCK.matcher(evidence);
    while (blockMatcher.find()) {
      Matcher identifier = IDENTIFIER.matcher(blockMatcher.group(1));
      while (identifier.find()) {
        String value = identifier.group(1);
        if (!Set.of("params", "string", "number", "boolean")
            .contains(value.toLowerCase(Locale.ROOT))) {
          params.add(value);
        }
      }
    }
    return Set.copyOf(params);
  }

  /**
   * Reads only explicitly labelled query-parameter lists from endpoint-local context.
   *
   * <p>Provider plans do not always prefix every declared parameter with {@code ?}; for example,
   * "query parameters: q, page, page_size" is still an authoritative producer declaration. The
   * label and two-or-more-identifier list are required so ordinary prose and response fields never
   * become transport parameters.
   */
  private static void addNamedQueryParams(LinkedHashSet<String> params, Matcher lists) {
    while (lists.find()) {
      Matcher identifier = IDENTIFIER.matcher(lists.group(1));
      while (identifier.find()) {
        String value = identifier.group(1);
        if (!Set.of("and", "or", "query", "parameter", "parameters")
            .contains(value.toLowerCase(Locale.ROOT))) {
          params.add(value);
        }
      }
    }
  }

  private static String operationContext(String evidence, int start, int end) {
    int previousEndpointEnd = -1;
    Matcher previousEndpoint = ENDPOINT.matcher(evidence);
    while (previousEndpoint.find() && previousEndpoint.start() < start) {
      previousEndpointEnd = previousEndpoint.end();
    }
    int lineStart = evidence.lastIndexOf('\n', start);
    int normalizedLineStart = lineStart < 0 ? 0 : lineStart + 1;
    int left = previousEndpointEnd >= normalizedLineStart ? start : normalizedLineStart;
    Matcher nextEndpoint = ENDPOINT.matcher(evidence);
    int right = nextEndpoint.find(end) ? nextEndpoint.start() : evidence.length();
    int lineEnd = evidence.indexOf('\n', end);
    if (lineEnd >= 0) right = Math.min(right, lineEnd);
    return evidence.substring(left, right).toLowerCase(Locale.ROOT);
  }

  private static String lastSegment(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  record ApiEvidence(
      String taskId,
      String method,
      String rawPath,
      String path,
      Set<String> domainTokens,
      Set<String> declaredQueryParams,
      Set<String> responseFields,
      String context) {}

  private record RoutePrefix(int sectionStart, int offset, int sectionEnd, String path) {}
}
