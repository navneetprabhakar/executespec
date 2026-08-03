package com.navneet.executespec.contractsurface;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses only explicit {@code METHOD /path} source constraints without guessing ambiguous prose.
 */
public final class ExactHttpConstraintParser {

  private static final Pattern EXACT_ROUTE =
      Pattern.compile(
          "(?i)(?:^|\\s|[`(])"
              + "(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)\\s+"
              + "(/[A-Za-z0-9_~!$&'()*+=:@%{}./-]+)");
  private static final Pattern REMOVAL_PREFIX =
      Pattern.compile("(?i).*\\b(?:remove|delete|retire|drop)\\s*$", Pattern.DOTALL);
  private static final Pattern FORBIDDEN_PREFIX =
      Pattern.compile(
          "(?i).*\\b(?:reject|deny|disallow|forbid|unsupported|"
              + "do\\s+not\\s+support|must\\s+not\\s+expose)\\s*$",
          Pattern.DOTALL);
  private static final Pattern FORBIDDEN_SUFFIX =
      Pattern.compile(
          "(?i)(?:^|.*\\b)(?:must|should|shall|is|are|has\\s+to|have\\s+to)?\\s*"
              + "(?:be\\s+)?(?:unsupported|not\\s+allowed)\\b.*"
              + "|.*\\b(?:rejected|denied|disallowed|forbidden)\\b.*"
              + "\\b(?:HTTP\\s+)?405\\b.*"
              + "|.*\\b(?:return|returns|respond|responds)\\s+(?:with\\s+)?(?:HTTP\\s+)?405\\b.*"
              + "|.*\\b(?:HTTP\\s+)?405\\s+(?:Method\\s+Not\\s+Allowed|response)\\b.*"
              + "|.*\\bMethod\\s+Not\\s+Allowed\\b.*");

  private ExactHttpConstraintParser() {}

  public static List<Constraint> parse(String text) {
    if (text == null || text.isBlank()) return List.of();
    List<Match> matches = new ArrayList<>();
    Matcher matcher = EXACT_ROUTE.matcher(text);
    while (matcher.find()) {
      String path = trimTrailingPunctuation(matcher.group(2));
      matches.add(new Match(matcher.start(), matcher.end(), matcher.group(1), path));
    }
    List<Constraint> constraints = new ArrayList<>();
    for (int index = 0; index < matches.size(); index++) {
      Match match = matches.get(index);
      String prefix = text.substring(Math.max(0, match.start() - 64), match.start());
      if (REMOVAL_PREFIX.matcher(prefix).matches()) continue;
      int nextRouteStart =
          index + 1 < matches.size() ? matches.get(index + 1).start() : text.length();
      String suffix = boundedClause(text.substring(match.end(), nextRouteStart));
      Polarity polarity =
          FORBIDDEN_PREFIX.matcher(prefix).matches() || FORBIDDEN_SUFFIX.matcher(suffix).matches()
              ? Polarity.METHOD_NOT_ALLOWED
              : Polarity.REQUIRED;
      constraints.add(new Constraint(match.method(), match.path(), polarity));
    }
    return List.copyOf(constraints);
  }

  private static String boundedClause(String suffix) {
    int boundary = suffix.length();
    for (int index = 0; index < suffix.length(); index++) {
      char value = suffix.charAt(index);
      if (value == '\n' || value == '\r' || value == ';' || value == '!' || value == '?') {
        boundary = index;
        break;
      }
      if (value == '.'
          && (index + 1 == suffix.length() || Character.isWhitespace(suffix.charAt(index + 1)))) {
        boundary = index;
        break;
      }
    }
    return suffix.substring(0, boundary);
  }

  private static String trimTrailingPunctuation(String path) {
    while (path.length() > 1 && ".,;:)`".indexOf(path.charAt(path.length() - 1)) >= 0) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  public enum Polarity {
    REQUIRED,
    METHOD_NOT_ALLOWED
  }

  public record Constraint(String method, String path, Polarity polarity) {
    public Constraint {
      method = method.toUpperCase(Locale.ROOT);
      polarity = polarity == null ? Polarity.REQUIRED : polarity;
    }
  }

  private record Match(int start, int end, String method, String path) {}
}
