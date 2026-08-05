package com.navneet.executespec.codeintel;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Base64;
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
  private static final Pattern TYPED_ROUTE_FACT =
      Pattern.compile(
          "(?m)^- Route-V1: method=([A-Za-z0-9+/=]+);path=([A-Za-z0-9+/=]+);"
              + "mode=(SERIALIZED_BODY|VIEW_NAME|NOT_ESTABLISHED);source="
              + "([A-Za-z0-9+/=]+);handler=([A-Za-z0-9+/=]*);returnType="
              + "([A-Za-z0-9+/=]*)$");
  private static final Pattern REPOSITORY_FACT = Pattern.compile("(?m)^- Repository: `([^`]+)`$");
  private static final Pattern SOURCE_REVISION_FACT =
      Pattern.compile("(?m)^- Source revision: `([0-9a-fA-F]{40})`$");
  private static final Pattern ROUTE_CLAIM =
      Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY)\\b\\s+`?(/[^\\s`,;:)]+)`?");
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
    Matcher fenced = FENCED_BLOCK.matcher(markdown);
    int cursor = 0;
    while (fenced.find()) {
      Optional<String> reason =
          unsupportedInProse(markdown.substring(cursor, fenced.start()), evidence);
      if (reason.isPresent()) return reason;
      cursor = fenced.end();
    }
    return unsupportedInProse(markdown.substring(cursor), evidence);
  }

  private static Optional<String> unsupportedInProse(String prose, FactEvidence evidence) {
    for (String line : prose.split("(?<=\\n)", -1)) {
      if (isHorizontalRule(line)) continue;
      Optional<String> reason = unsupportedInFragment(headingText(line), evidence);
      if (reason.isPresent()) return reason;
    }
    return Optional.empty();
  }

  private static String sanitizeProse(String prose, FactEvidence evidence) {
    StringBuilder result = new StringBuilder(prose.length());
    for (String line : prose.split("(?<=\\n)", -1)) {
      if (isHorizontalRule(line)) {
        result.append(line);
      } else if (isMarkdownHeading(line)) {
        result.append(sanitizeHeading(line, evidence));
      } else {
        result.append(sanitizeFragment(line, evidence));
      }
    }
    return result.toString();
  }

  private static String sanitizeFragment(String prose, FactEvidence evidence) {
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

  private static Optional<String> unsupportedInFragment(String prose, FactEvidence evidence) {
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

  private static boolean isMarkdownHeading(String line) {
    String stripped = line.strip();
    return stripped.matches("#{1,6}\\s+.*");
  }

  private static boolean isHorizontalRule(String line) {
    return line.strip().matches("(?:-{3,}|_{3,}|\\*{3,})");
  }

  private static String headingText(String line) {
    Matcher heading = Pattern.compile("^(\\s*#{1,6}\\s+)(.*?)(\\R?)$").matcher(line);
    return heading.matches() ? heading.group(2) : line;
  }

  private static String sanitizeHeading(String line, FactEvidence evidence) {
    Matcher heading = Pattern.compile("^(\\s*#{1,6}\\s+)(.*?)(\\R?)$").matcher(line);
    if (!heading.matches()) return sanitizeFragment(line, evidence);
    String sanitized = sanitizeFragment(heading.group(2), evidence);
    if (sanitized.startsWith("> ")) sanitized = sanitized.substring(2);
    return heading.group(1) + sanitized + heading.group(3);
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
      List<RouteMention> mentions = routeMentions(sentence);
      if (mentions.size() != 1 || !mentions.getFirst().path().equals(path)) {
        return Optional.of(
            "the route claim must contain one adjacent HTTP method and exact path; "
                + NOT_ESTABLISHED);
      }
      RouteMention mention = mentions.getFirst();
      RouteResolution resolution = evidence.resolve(sentence, path, mention.method());
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

  private record RouteMention(String method, String path) {}

  private static List<RouteMention> routeMentions(String sentence) {
    java.util.ArrayList<RouteMention> mentions = new java.util.ArrayList<>();
    Matcher matcher = ROUTE_CLAIM.matcher(sentence);
    while (matcher.find()) {
      mentions.add(new RouteMention(matcher.group(1), stripTrailingPunctuation(matcher.group(2))));
    }
    return List.copyOf(mentions);
  }

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

    RouteResolution resolve(String sentence, String path, String method) {
      String repository = referencedRepository(sentence);
      if (repository == null) {
        return RouteResolution.failed(
            "the repository owning route `" + path + "` is not established in the claim");
      }
      String selectedRepository = repository;
      List<String> modes =
          routeModes.entrySet().stream()
              .filter(entry -> entry.getKey().repository().equals(selectedRepository))
              .filter(entry -> entry.getKey().path().equals(path))
              .filter(entry -> entry.getKey().method().equals(method))
              .map(Map.Entry::getValue)
              .distinct()
              .toList();
      if (modes.isEmpty()) {
        String route = method + " " + path;
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
      List<String> matches =
          repositories.stream()
              .filter(repository -> containsExactRepository(sentence, repository))
              .toList();
      return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean containsExactRepository(String sentence, String repository) {
      return Pattern.compile(
              "(?i)(?<![A-Za-z0-9_./-])" + Pattern.quote(repository) + "(?![A-Za-z0-9_./-])")
          .matcher(sentence)
          .find();
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
        Matcher revisionFact = SOURCE_REVISION_FACT.matcher(block);
        String revision = revisionFact.find() ? revisionFact.group(1) : null;
        boolean complete =
            block.contains("- Evidence-Format: `EXECUTESPEC_PROJECT_FACTS_V2`")
                && block.contains("- Status: `AVAILABLE`")
                && repository != null
                && revision != null;
        allComplete &= complete;
        if (repository != null && !repositories.add(repository)) return unavailableEvidence();
        if (complete) {
          String typedRoutes = uniqueTypedRouteSection(block);
          if (typedRoutes != null) {
            Matcher route = TYPED_ROUTE_FACT.matcher(typedRoutes);
            while (route.find()) {
              String method = decode(route.group(1), 16);
              String path = decode(route.group(2), 4_096);
              String source = decode(route.group(4), 4_096);
              String handler = decode(route.group(5), 512);
              String returnType = decode(route.group(6), 1_024);
              if (method == null
                  || path == null
                  || source == null
                  || handler == null
                  || returnType == null
                  || !method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY")
                  || !path.startsWith("/")
                  || path.chars().anyMatch(Character::isISOControl)) continue;
              RouteKey key = new RouteKey(repository, method, path);
              String previous = routes.putIfAbsent(key, route.group(3));
              if (previous != null && !previous.equals(route.group(3))) {
                routes.put(key, "AMBIGUOUS");
              }
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

    private static String uniqueTypedRouteSection(String block) {
      int begin = block.indexOf(DeterministicProjectFactEvidence.ROUTES_BEGIN);
      if (begin < 0
          || block.indexOf(DeterministicProjectFactEvidence.ROUTES_BEGIN, begin + 1) >= 0) {
        return null;
      }
      int contentStart = begin + DeterministicProjectFactEvidence.ROUTES_BEGIN.length();
      int end = block.indexOf(DeterministicProjectFactEvidence.ROUTES_END, contentStart);
      if (end < 0 || block.indexOf(DeterministicProjectFactEvidence.ROUTES_END, end + 1) >= 0) {
        return null;
      }
      return block.substring(contentStart, end);
    }

    private static String decode(String encoded, int maxDecodedBytes) {
      if (encoded == null || encoded.length() > ((maxDecodedBytes + 2) / 3) * 4) return null;
      try {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length > maxDecodedBytes
            || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) return null;
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString();
      } catch (IllegalArgumentException | CharacterCodingException malformed) {
        return null;
      }
    }
  }
}
