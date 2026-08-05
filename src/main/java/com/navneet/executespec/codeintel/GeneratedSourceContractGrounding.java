package com.navneet.executespec.codeintel;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes exhaustive single-symbol contracts that exact source evidence does not establish. */
final class GeneratedSourceContractGrounding {

  private static final Pattern CODEMAP_SYMBOL =
      Pattern.compile(
          "(?m)^-\\s+`(?:(?:class|interface|record|enum|def|func|fn)\\s+)?"
              + "([A-Za-z_$][A-Za-z0-9_$]*)`\\s*$");
  private static final Pattern CONTRACT_CUE =
      Pattern.compile(
          "(?i)\\b(?:allow(?:s|ed)?|accept(?:s|ed)?|permit(?:s|ted)?|support(?:s|ed)?|"
              + "validat(?:e[sd]?|ing)|valid|only|restricted\\s+to|one\\s+of)\\b"
              + ".{0,220}\\b(?:fields?|options?|values?|statuses?|states?|modes?|types?|"
              + "kinds?|keys?|directions?)\\b"
              + "|\\b(?:fields?|options?|values?|statuses?|states?|modes?|types?|kinds?|"
              + "keys?|directions?)\\b.{0,120}\\b(?:allow(?:s|ed)?|accept(?:s|ed)?|"
              + "permit(?:s|ted)?|support(?:s|ed)?|validat(?:e[sd]?|ing)|valid|only|"
              + "restricted\\s+to)\\b");
  private static final Pattern PARENTHESIZED_VALUES = Pattern.compile("\\(([^)\\r\\n]{1,500})\\)");
  private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\r\\n]+)`");
  private static final Pattern QUOTED_VALUE =
      Pattern.compile("(?:'([^'\\r\\n]+)'|\\\"([^\\\"\\r\\n]+)\\\"|`([^`\\r\\n$]+)`)");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
  private static final Pattern FUNCTION_SIGNATURE =
      Pattern.compile(
          "(?s)\\bfunction\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:<[^>{}\\r\\n]+>)?\\s*\\(([^)]*)\\)");
  private static final Pattern SET_DECLARATION =
      Pattern.compile(
          "(?s)\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*"
              + "new\\s+Set\\s*\\(\\s*\\[([^]]{1,800})]\\s*\\)");
  private static final Pattern ARRAY_DECLARATION =
      Pattern.compile(
          "(?s)\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*" + "\\[([^]]{1,800})]");
  private static final Pattern DIRECT_ARRAY_MEMBERSHIP =
      Pattern.compile(
          "(?s)\\[([^]]{1,800})]\\s*\\.includes\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)");
  private static final Pattern MARKDOWN_PREFIX =
      Pattern.compile("^(\\s*(?:(?:#{1,6}|[-*+]|>)\\s+))");
  private static final String GROUNDING_NOTE = "not established by complete exact-source evidence";

  private GeneratedSourceContractGrounding() {}

  /**
   * Sanitizes repository prose using only exact evidence bound to the expected repository and SHA.
   */
  static String sanitizeForRepository(
      String markdown,
      String sourceEvidence,
      String expectedRepository,
      String expectedSourceRevision) {
    Map<String, String> bodies =
        ExportedFunctionEvidence.completeContentBySymbol(
            sourceEvidence, expectedRepository, expectedSourceRevision);
    return sanitize(markdown, sourceEvidence, bodies);
  }

  /**
   * Sanitizes combined or summary prose where a symbol cannot be bound to one repository revision.
   */
  static String sanitizeWithoutSourceAuthority(String markdown, String sourceEvidence) {
    return sanitize(markdown, sourceEvidence, Map.of());
  }

  private static String sanitize(
      String markdown, String sourceEvidence, Map<String, String> completeBodies) {
    if (markdown == null || markdown.isEmpty()) return markdown;
    Set<String> symbols = codemapSymbols(sourceEvidence);
    if (symbols.isEmpty()) return markdown;

    StringBuilder result = new StringBuilder(markdown.length());
    StringBuilder prose = new StringBuilder();
    String fence = null;
    int cursor = 0;
    while (cursor < markdown.length()) {
      int newline = markdown.indexOf('\n', cursor);
      int end = newline < 0 ? markdown.length() : newline + 1;
      String line = markdown.substring(cursor, end);
      String trimmed = line.stripLeading();
      String marker = fenceMarker(trimmed);
      if (fence != null) {
        flushProse(result, prose, symbols, completeBodies);
        result.append(line);
        if (marker != null && marker.equals(fence)) fence = null;
      } else if (marker != null) {
        flushProse(result, prose, symbols, completeBodies);
        result.append(line);
        fence = marker;
      } else if (isIndentedCode(line)) {
        flushProse(result, prose, symbols, completeBodies);
        result.append(line);
      } else {
        prose.append(line);
      }
      cursor = end;
    }
    flushProse(result, prose, symbols, completeBodies);
    return result.toString();
  }

  private static void flushProse(
      StringBuilder result,
      StringBuilder prose,
      Set<String> symbols,
      Map<String, String> completeBodies) {
    if (prose.isEmpty()) return;
    String text = prose.toString();
    int cursor = 0;
    Matcher separator = Pattern.compile("\\n[ \\t]*\\n").matcher(text);
    while (separator.find()) {
      result.append(
          sanitizeParagraph(text.substring(cursor, separator.start()), symbols, completeBodies));
      result.append(text, separator.start(), separator.end());
      cursor = separator.end();
    }
    result.append(sanitizeParagraph(text.substring(cursor), symbols, completeBodies));
    prose.setLength(0);
  }

  private static String sanitizeParagraph(
      String paragraph, Set<String> symbols, Map<String, String> completeBodies) {
    if (paragraph.isBlank()) return paragraph;
    String normalized = paragraph.replaceAll("\\s+", " ").trim();
    ContractClaim claim = contractClaim(normalized, symbols);
    if (claim == null || established(claim, completeBodies.get(claim.symbol()))) return paragraph;
    return degrade(paragraph, claim.symbol());
  }

  private static ContractClaim contractClaim(String paragraph, Set<String> symbols) {
    if (isCanonicalGroundingNote(paragraph)) return null;
    String plain = paragraph.replace("`", "");
    if (!CONTRACT_CUE.matcher(plain).find()) return null;
    String symbol = firstMentionedSymbol(paragraph, symbols);
    if (symbol == null) return null;
    return new ContractClaim(symbol, claimedValues(paragraph, symbol));
  }

  private static Set<String> claimedValues(String paragraph, String symbol) {
    Matcher parenthesized = PARENTHESIZED_VALUES.matcher(paragraph);
    while (parenthesized.find()) {
      Set<String> values = inlineValues(parenthesized.group(1), symbol);
      if (!values.isEmpty()) return values;
    }
    Matcher inline = INLINE_CODE.matcher(paragraph);
    Set<String> values = new LinkedHashSet<>();
    while (inline.find()) {
      String value = inline.group(1).trim();
      if (IDENTIFIER.matcher(value).matches() && !value.equalsIgnoreCase(symbol)) values.add(value);
    }
    return Set.copyOf(values);
  }

  private static boolean established(ContractClaim claim, String completeBody) {
    if (completeBody == null || completeBody.isBlank() || claim.values().isEmpty()) return false;
    // Structure must exist in live code: commented-out or string-quoted pseudo-code cannot
    // authorize a contract, while literal values are read from the original matched offsets.
    String structure = ExportedFunctionEvidence.maskNonCode(completeBody);
    Set<String> parameters = functionParameters(structure, claim.symbol());
    if (parameters.isEmpty()) return false;

    Matcher sets = SET_DECLARATION.matcher(structure);
    while (sets.find()) {
      if (boundMembershipMatches(
          structure,
          sets.group(1),
          "has",
          completeBody.substring(sets.start(2), sets.end(2)),
          claim.values(),
          parameters)) {
        return true;
      }
    }
    Matcher arrays = ARRAY_DECLARATION.matcher(structure);
    while (arrays.find()) {
      if (boundMembershipMatches(
          structure,
          arrays.group(1),
          "includes",
          completeBody.substring(arrays.start(2), arrays.end(2)),
          claim.values(),
          parameters)) {
        return true;
      }
    }
    Matcher direct = DIRECT_ARRAY_MEMBERSHIP.matcher(structure);
    while (direct.find()) {
      if (parameters.contains(direct.group(2))
          && literalValues(completeBody.substring(direct.start(1), direct.end(1)))
              .equals(claim.values())) {
        return true;
      }
    }
    return false;
  }

  private static boolean boundMembershipMatches(
      String body,
      String receiver,
      String method,
      String literals,
      Set<String> claimed,
      Set<String> parameters) {
    Pattern membership =
        Pattern.compile(
            "(?<![A-Za-z0-9_$])"
                + Pattern.quote(receiver)
                + "\\."
                + method
                + "\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)");
    Matcher calls = membership.matcher(body);
    while (calls.find()) {
      if (parameters.contains(calls.group(1)) && literalValues(literals).equals(claimed))
        return true;
    }
    return false;
  }

  private static Set<String> functionParameters(String body, String symbol) {
    Matcher signature = FUNCTION_SIGNATURE.matcher(body);
    while (signature.find()) {
      if (!signature.group(1).equals(symbol)) continue;
      Set<String> parameters = new LinkedHashSet<>();
      for (String raw : signature.group(2).split(",")) {
        String candidate = raw.trim().replaceFirst("\\s*=.*$", "");
        candidate = candidate.replaceFirst("^\\.\\.\\.", "");
        candidate = candidate.replaceFirst("\\?.*$", "");
        candidate = candidate.replaceFirst(":.*$", "").trim();
        if (IDENTIFIER.matcher(candidate).matches()) parameters.add(candidate);
      }
      return Set.copyOf(parameters);
    }
    return Set.of();
  }

  private static Set<String> inlineValues(String text, String symbol) {
    Set<String> values = new LinkedHashSet<>();
    Matcher inline = INLINE_CODE.matcher(text);
    while (inline.find()) {
      String candidate = inline.group(1).trim();
      if (IDENTIFIER.matcher(candidate).matches() && !candidate.equalsIgnoreCase(symbol)) {
        values.add(candidate);
      }
    }
    if (!values.isEmpty()) return Set.copyOf(values);
    for (String raw : text.split(",|\\band\\b")) {
      String candidate = raw.trim().replaceAll("^[`'\\\"]|[`'\\\"]$", "");
      if (IDENTIFIER.matcher(candidate).matches() && !candidate.equalsIgnoreCase(symbol)) {
        values.add(candidate);
      }
    }
    return Set.copyOf(values);
  }

  private static Set<String> literalValues(String source) {
    Set<String> values = new LinkedHashSet<>();
    Matcher literal = QUOTED_VALUE.matcher(source);
    while (literal.find()) {
      String value = literal.group(1) != null ? literal.group(1) : literal.group(2);
      if (value == null) value = literal.group(3);
      if (value != null && IDENTIFIER.matcher(value).matches()) values.add(value);
    }
    return Set.copyOf(values);
  }

  private static String degrade(String paragraph, String symbol) {
    String trailing = paragraph.endsWith("\n") ? "\n" : "";
    String prefix = "";
    Matcher wrapper = MARKDOWN_PREFIX.matcher(paragraph);
    if (wrapper.find()) prefix = wrapper.group(1);
    boolean table = paragraph.stripLeading().startsWith("|");
    String note = "Source contract for `" + symbol + "`: " + GROUNDING_NOTE + ".";
    return table ? "| " + note + " |" + trailing : prefix + note + trailing;
  }

  private static boolean isCanonicalGroundingNote(String paragraph) {
    String unwrapped = paragraph.trim();
    unwrapped = unwrapped.replaceFirst("^(?:#{1,6}|[-*+]|>)\\s+", "");
    if (unwrapped.startsWith("|") && unwrapped.endsWith("|")) {
      unwrapped = unwrapped.substring(1, unwrapped.length() - 1).trim();
    }
    return unwrapped.matches(
        "Source contract for `[A-Za-z_$][A-Za-z0-9_$]*`: " + Pattern.quote(GROUNDING_NOTE) + "\\.");
  }

  private static Set<String> codemapSymbols(String evidence) {
    Set<String> symbols = new LinkedHashSet<>();
    if (evidence == null || evidence.isBlank()) return symbols;
    Matcher matcher = CODEMAP_SYMBOL.matcher(evidence);
    while (matcher.find()) symbols.add(matcher.group(1));
    return symbols;
  }

  private static String firstMentionedSymbol(String paragraph, Set<String> symbols) {
    String lower = paragraph.toLowerCase(Locale.ROOT);
    String first = null;
    int firstIndex = Integer.MAX_VALUE;
    for (String symbol : symbols) {
      Matcher occurrence =
          Pattern.compile(
                  "(?<![A-Za-z0-9_$])"
                      + Pattern.quote(symbol.toLowerCase(Locale.ROOT))
                      + "(?![A-Za-z0-9_$])")
              .matcher(lower);
      if (occurrence.find() && occurrence.start() < firstIndex) {
        first = symbol;
        firstIndex = occurrence.start();
      }
    }
    return first;
  }

  private static String fenceMarker(String trimmedLine) {
    if (trimmedLine.startsWith("```")) return "```";
    if (trimmedLine.startsWith("~~~")) return "~~~";
    return null;
  }

  private static boolean isIndentedCode(String line) {
    return line.startsWith("    ") || line.startsWith("\t");
  }

  private record ContractClaim(String symbol, Set<String> values) {}
}
