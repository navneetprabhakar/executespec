package com.navneet.executespec.codeintel;

import java.text.BreakIterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sanitizes a narrow set of factual claims against exact project-document evidence. */
final class GeneratedProjectFactGrounding {

  private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```.*?(?:```|\\z)");
  private static final Pattern ROUTE_FACT =
      Pattern.compile(
          "(?m)^- `(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY) (/[^` ]*)` — .*?response"
              + " mode `([A-Z_]+)`;");
  private static final Pattern REPOSITORY_FACT = Pattern.compile("(?m)^- Repository: `([^`]+)`$");
  private static final Pattern HTTP_METHOD_CLAIM =
      Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY)\\b");
  private static final Pattern PATH = Pattern.compile("(?<![A-Za-z0-9_.-])(/[^\\s`,;:)]+)");
  private static final Pattern SCHEMA_OWNER =
      Pattern.compile(
          "(?i)(?:\\b(?:flyway|liquibase|hibernate|jpa)\\b.{0,100}"
              + "\\b(?:manag(?:e[sd]?|ing)|own(?:s|ed|ing)?|creat(?:e[sd]?|ing)|"
              + "initializ(?:e[sd]?|ing)|migrat(?:e[sd]?|ing|ion)|provision(?:s|ed|ing)?)\\b|"
              + "\\b(?:manag(?:e[sd]?|ing)|own(?:s|ed|ing)?|creat(?:e[sd]?|ing)|"
              + "initializ(?:e[sd]?|ing)|migrat(?:e[sd]?|ing|ion)|provision(?:s|ed|ing)?)\\b"
              + ".{0,100}\\b(?:flyway|liquibase|hibernate|jpa)\\b)");
  private static final Pattern COMPOSE =
      Pattern.compile("(?i)\\b(?:docker[- ]?compose|compose (?:file|stack))\\b");
  private static final Pattern COMPOSE_APP_CLAIM =
      Pattern.compile(
          "(?i)\\b(?:start(?:s|ed|ing)?|run(?:s|ning)?|launch(?:es|ed|ing)?|"
              + "orchestrat(?:e[sd]?|ing)|bring(?:s|ing)? up|include[sd]?|contain[sd]?|"
              + "provision(?:s|ed|ing)?)\\b.{0,120}"
              + "\\b(?:application|app(?:lication)? service|web service|full stack)\\b|"
              + "\\b(?:application|app(?:lication)? service|web service|full stack)\\b.{0,120}"
              + "\\b(?:start(?:s|ed|ing)?|run(?:s|ning)?|launch(?:es|ed|ing)?|"
              + "orchestrat(?:e[sd]?|ing)|include[sd]?|contain[sd]?|provision(?:s|ed|ing)?)\\b");
  private static final Pattern VIEW_CLAIM =
      Pattern.compile("(?i)\\b(?:view|template|thymeleaf|html page|render(?:s|ed|ing)?)\\b");
  private static final Pattern BODY_CLAIM =
      Pattern.compile(
          "(?i)\\b(?:json|serialized (?:body|response)|response body|rest response|returns? an?"
              + " object)\\b");
  private static final String NOT_ESTABLISHED =
      "deterministic exact-revision evidence does not establish this claim";

  private GeneratedProjectFactGrounding() {}

  static String sanitize(String markdown, String sourceEvidence) {
    if (markdown == null || markdown.isEmpty()) return markdown;
    FactEvidence evidence = FactEvidence.parse(sourceEvidence);
    Matcher fenced = FENCED_BLOCK.matcher(markdown);
    StringBuilder result = new StringBuilder(markdown.length());
    int cursor = 0;
    while (fenced.find()) {
      result.append(sanitizeProse(markdown.substring(cursor, fenced.start()), evidence));
      result.append(fenced.group());
      cursor = fenced.end();
    }
    result.append(sanitizeProse(markdown.substring(cursor), evidence));
    return result.toString();
  }

  static Optional<String> unsupportedFactualClaim(String markdown, String sourceEvidence) {
    if (markdown == null || markdown.isBlank()) return Optional.empty();
    FactEvidence evidence = FactEvidence.parse(sourceEvidence);
    String prose = markdown;
    BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
    sentences.setText(prose);
    int start = sentences.first();
    for (int end = sentences.next();
        end != BreakIterator.DONE;
        start = end, end = sentences.next()) {
      Optional<String> reason = unsupported(prose.substring(start, end), evidence);
      if (reason.isPresent()) return reason;
    }
    return Optional.empty();
  }

  private static String sanitizeProse(String prose, FactEvidence evidence) {
    BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
    sentences.setText(prose);
    StringBuilder result = new StringBuilder(prose.length());
    int start = sentences.first();
    for (int end = sentences.next();
        end != BreakIterator.DONE;
        start = end, end = sentences.next()) {
      String sentence = prose.substring(start, end);
      Optional<String> reason = unsupported(sentence, evidence);
      if (reason.isEmpty()) {
        result.append(sentence);
        continue;
      }
      int leading = 0;
      while (leading < sentence.length() && Character.isWhitespace(sentence.charAt(leading)))
        leading++;
      int trailing = sentence.length();
      while (trailing > leading && Character.isWhitespace(sentence.charAt(trailing - 1)))
        trailing--;
      result.append(sentence, 0, leading);
      result.append("> Not established: ").append(reason.orElseThrow()).append('.');
      result.append(sentence, trailing, sentence.length());
    }
    return result.toString();
  }

  private static Optional<String> unsupported(String sentence, FactEvidence evidence) {
    if (sentence.contains("Not established:") && sentence.contains(NOT_ESTABLISHED)) {
      return Optional.empty();
    }
    Matcher schema = SCHEMA_OWNER.matcher(sentence);
    if (schema.find()) {
      String lower = sentence.toLowerCase(Locale.ROOT);
      if (lower.contains("flyway")) {
        return Optional.of("Flyway schema ownership is not established; " + NOT_ESTABLISHED);
      }
      if (lower.contains("liquibase")) {
        return Optional.of("Liquibase schema ownership is not established; " + NOT_ESTABLISHED);
      }
      if (lower.contains("hibernate") || lower.contains("jpa")) {
        return Optional.of("Hibernate/JPA schema ownership is not established; " + NOT_ESTABLISHED);
      }
    }

    if (COMPOSE.matcher(sentence).find() && COMPOSE_APP_CLAIM.matcher(sentence).find()) {
      return Optional.of(
          "Compose application-service startup is not established; " + NOT_ESTABLISHED);
    }

    boolean viewClaim = VIEW_CLAIM.matcher(sentence).find();
    boolean bodyClaim = BODY_CLAIM.matcher(sentence).find();
    if (!viewClaim && !bodyClaim) return Optional.empty();

    Matcher paths = PATH.matcher(sentence);
    while (paths.find()) {
      String path = stripTrailingPunctuation(paths.group(1));
      RouteResolution resolution = evidence.resolve(sentence, path);
      if (resolution.failureReason() != null) {
        return Optional.of(resolution.failureReason() + "; " + NOT_ESTABLISHED);
      }
      String mode = resolution.mode();
      if (mode == null || "NOT_ESTABLISHED".equals(mode)) {
        return Optional.of(
            "the exact handler response mode for `"
                + path
                + "` is not established; "
                + NOT_ESTABLISHED);
      }
      if (("SERIALIZED_BODY".equals(mode) || "AMBIGUOUS".equals(mode)) && viewClaim) {
        return Optional.of(
            "the exact handler evidence records `"
                + path
                + "` as `SERIALIZED_BODY`, not a view; "
                + NOT_ESTABLISHED);
      }
      if (("VIEW_NAME".equals(mode) || "AMBIGUOUS".equals(mode)) && bodyClaim) {
        return Optional.of(
            "the exact handler evidence records `"
                + path
                + "` as `VIEW_NAME`, not a serialized response; "
                + NOT_ESTABLISHED);
      }
    }
    return Optional.empty();
  }

  private static String stripTrailingPunctuation(String path) {
    int end = path.length();
    while (end > 1 && ".!?]}'\"".indexOf(path.charAt(end - 1)) >= 0) end--;
    return path.substring(0, end);
  }

  private record RouteKey(String repository, String method, String path) {}

  private record RouteResolution(String mode, String failureReason) {
    private static RouteResolution resolved(String mode) {
      return new RouteResolution(mode, null);
    }

    private static RouteResolution failed(String reason) {
      return new RouteResolution(null, reason);
    }
  }

  private record FactEvidence(
      boolean available,
      boolean negativeEvidenceComplete,
      Set<String> repositories,
      Map<RouteKey, String> routeModes) {

    RouteResolution resolve(String sentence, String path) {
      String repository = referencedRepository(sentence);
      if (repository == null) {
        if (repositories.size() != 1) {
          return RouteResolution.failed(
              "the repository owning route `" + path + "` is not established in the claim");
        }
        repository = repositories.iterator().next();
      }
      Matcher methodClaim = HTTP_METHOD_CLAIM.matcher(sentence);
      String method = methodClaim.find() ? methodClaim.group(1) : null;
      String selectedRepository = repository;
      List<String> modes =
          routeModes.entrySet().stream()
              .filter(entry -> entry.getKey().repository().equals(selectedRepository))
              .filter(entry -> entry.getKey().path().equals(path))
              .filter(entry -> method == null || entry.getKey().method().equals(method))
              .map(Map.Entry::getValue)
              .distinct()
              .toList();
      if (modes.isEmpty()) {
        String route = method == null ? path : method + " " + path;
        return RouteResolution.failed(
            "the exact handler evidence for `"
                + route
                + "` in repository `"
                + repository
                + "` is missing");
      }
      if (modes.size() != 1) return RouteResolution.resolved("AMBIGUOUS");
      return RouteResolution.resolved(modes.getFirst());
    }

    private String referencedRepository(String sentence) {
      String lower = sentence.toLowerCase(Locale.ROOT);
      List<String> matches =
          repositories.stream()
              .filter(repository -> lower.contains(repository.toLowerCase(Locale.ROOT)))
              .toList();
      return matches.size() == 1 ? matches.getFirst() : null;
    }

    static FactEvidence parse(String sourceEvidence) {
      if (sourceEvidence == null) return unavailableEvidence();
      int cursor = 0;
      int blocks = 0;
      boolean allComplete = true;
      Set<String> repositories = new LinkedHashSet<>();
      Map<RouteKey, String> routes = new LinkedHashMap<>();
      while (true) {
        int begin = sourceEvidence.indexOf(DeterministicProjectFactEvidence.BEGIN, cursor);
        if (begin < 0) break;
        int end = sourceEvidence.indexOf(DeterministicProjectFactEvidence.END, begin);
        if (end < 0) return unavailableEvidence();
        String block = sourceEvidence.substring(begin, end);
        blocks++;
        Matcher repositoryFact = REPOSITORY_FACT.matcher(block);
        String repository = repositoryFact.find() ? repositoryFact.group(1) : null;
        boolean complete = block.contains("- Status: `AVAILABLE`") && repository != null;
        allComplete &= complete;
        if (repository != null) repositories.add(repository);
        if (complete) {
          Matcher route = ROUTE_FACT.matcher(block);
          while (route.find()) {
            RouteKey key = new RouteKey(repository, route.group(1), route.group(2));
            String previous = routes.putIfAbsent(key, route.group(3));
            if (previous != null && !previous.equals(route.group(3))) {
              routes.put(key, "AMBIGUOUS");
            }
          }
        }
        cursor = end + DeterministicProjectFactEvidence.END.length();
      }
      if (blocks == 0) return unavailableEvidence();
      return new FactEvidence(true, allComplete, Set.copyOf(repositories), Map.copyOf(routes));
    }

    private static FactEvidence unavailableEvidence() {
      return new FactEvidence(false, false, Set.of(), Map.of());
    }
  }
}
