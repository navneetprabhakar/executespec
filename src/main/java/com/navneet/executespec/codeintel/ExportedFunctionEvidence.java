package com.navneet.executespec.codeintel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Bounded exact-source evidence for exported named JavaScript and TypeScript functions. */
final class ExportedFunctionEvidence {

  static final String BEGIN = "<!-- EXECUTESPEC_EXPORTED_FUNCTION_EVIDENCE_V1_BEGIN -->";
  static final String END = "<!-- EXECUTESPEC_EXPORTED_FUNCTION_EVIDENCE_V1_END -->";
  static final int MAX_SYMBOLS = 20;
  static final int MAX_CHARS_PER_SYMBOL = 1_500;
  static final int MAX_BLOCK_CHARS = 20_000;

  private static final Pattern EXACT_SHA = Pattern.compile("[0-9a-fA-F]{40}");
  private static final Pattern SOURCE_PATH = Pattern.compile("(?i).+\\.(?:[cm]?js|jsx|tsx?)$");
  private static final Pattern EXPORTED_NAMED_FUNCTION =
      Pattern.compile(
          "(?m)^\\s*export\\s+(?:default\\s+)?(?:async\\s+)?function\\s+"
              + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:<[^>{}\\r\\n]+>)?\\s*\\(");
  private static final Pattern INTEGER = Pattern.compile("[0-9]+");
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final ObjectMapper JSON = new ObjectMapper();

  private ExportedFunctionEvidence() {}

  /**
   * Returns the one complete block bound to the requested repository revision, or {@code null}.
   * Partial, duplicate, mismatched, and marker-only blocks are never admissible grounding.
   */
  static Inspection inspectExactBlock(String markdown, String repository, String sourceRevision) {
    if (markdown == null
        || repository == null
        || repository.isBlank()
        || sourceRevision == null
        || !EXACT_SHA.matcher(sourceRevision).matches()) return Inspection.malformed();
    int begin = markdown.indexOf(BEGIN);
    if (begin < 0 && markdown.indexOf(END) < 0) return Inspection.absent();
    if (begin < 0
        || markdown.indexOf(END) < begin
        || markdown.indexOf(BEGIN, begin + BEGIN.length()) >= 0) {
      return Inspection.malformed();
    }
    int end = markdown.indexOf(END, begin + BEGIN.length());
    if (end < 0 || markdown.indexOf(END, end + END.length()) >= 0) {
      return Inspection.malformed();
    }
    String block = markdown.substring(begin, end + END.length());
    if (block.length() > MAX_BLOCK_CHARS) return Inspection.malformed();
    String normalizedRevision = sourceRevision.toLowerCase(java.util.Locale.ROOT);
    if (!block.startsWith(header(repository, normalizedRevision))) {
      return Inspection.malformed();
    }
    String emittedValue = field(block, "Emitted-Symbols");
    if (emittedValue == null || !INTEGER.matcher(emittedValue).matches()) {
      return Inspection.malformed();
    }
    int emitted;
    try {
      emitted = Integer.parseInt(emittedValue);
    } catch (NumberFormatException malformed) {
      return Inspection.malformed();
    }
    if (emitted < 1 || emitted > MAX_SYMBOLS) return Inspection.malformed();
    String repositoryTruncated = field(block, "Repository-Evidence-Truncated");
    if (!"true".equals(repositoryTruncated) && !"false".equals(repositoryTruncated)) {
      return Inspection.malformed();
    }
    StringBuilder canonical = new StringBuilder(header(repository, normalizedRevision));
    for (int ordinal = 1; ordinal <= emitted; ordinal++) {
      String heading = "### Exported function " + ordinal + "\n";
      int entryStart = block.indexOf(heading);
      if (entryStart < 0 || block.indexOf(heading, entryStart + heading.length()) >= 0) {
        return Inspection.malformed();
      }
      int entryEnd =
          ordinal == emitted
              ? block.indexOf("\n- Emitted-Symbols:", entryStart)
              : block.indexOf("### Exported function " + (ordinal + 1) + "\n", entryStart);
      String canonicalEntry =
          entryEnd < 0 ? null : canonicalEntry(block.substring(entryStart, entryEnd), ordinal);
      if (canonicalEntry == null) {
        return Inspection.malformed();
      }
      canonical.append(canonicalEntry);
    }
    if (block.contains("### Exported function " + (emitted + 1) + "\n")) {
      return Inspection.malformed();
    }
    canonical.append(finishSuffix(emitted, Boolean.parseBoolean(repositoryTruncated)));
    String admitted = block + "\n\n";
    return admitted.equals(canonical.toString())
        ? Inspection.valid(admitted)
        : Inspection.malformed();
  }

  /**
   * Returns complete function bodies from the one canonical exact-source block in {@code markdown}.
   * Arbitrary codemap prose, purpose summaries, truncated entries, and malformed envelopes never
   * become source-contract authority.
   */
  static Map<String, String> completeContentBySymbol(
      String markdown, String expectedRepository, String expectedSourceRevision) {
    Inspection inspection = inspectExactBlock(markdown, expectedRepository, expectedSourceRevision);
    if (inspection.status() != InspectionStatus.VALID) return Map.of();
    String block = inspection.block();
    try {
      String emittedValue = field(block, "Emitted-Symbols");
      if (emittedValue == null) return Map.of();

      int emitted = Integer.parseInt(emittedValue);
      Map<String, String> complete = new LinkedHashMap<>();
      for (int ordinal = 1; ordinal <= emitted; ordinal++) {
        int entryStart = block.indexOf("### Exported function " + ordinal + "\n");
        int entryEnd =
            ordinal == emitted
                ? block.indexOf("\n- Emitted-Symbols:", entryStart)
                : block.indexOf("### Exported function " + (ordinal + 1) + "\n", entryStart);
        if (entryStart < 0 || entryEnd < 0) return Map.of();
        String entry = block.substring(entryStart, entryEnd);
        if (!"false".equals(field(entry, "Truncated"))) continue;
        String symbolJson = field(entry, "Symbol-Json");
        String contentJson = field(entry, "Content-Json");
        if (symbolJson == null || contentJson == null) return Map.of();
        String symbol = JSON.readValue(symbolJson, String.class);
        String content = JSON.readValue(contentJson, String.class);
        if (complete.put(symbol, content) != null) return Map.of();
      }
      return Map.copyOf(complete);
    } catch (Exception malformed) {
      return Map.of();
    }
  }

  /**
   * Returns a same-length projection of {@code source} with comment bodies and string contents
   * blanked, so structural matching cannot be satisfied by commented-out or quoted pseudo-code.
   * Newlines survive and offsets are stable for exact-slice extraction from the original text.
   */
  static String maskNonCode(String source) {
    StringBuilder masked = new StringBuilder(source.length());
    ScanState state = new ScanState();
    int index = 0;
    while (index < source.length()) {
      boolean codeBefore = state.code();
      int consumed = state.consume(source, index);
      boolean codeAfter = state.code();
      int last = Math.min(consumed, source.length() - 1);
      for (int at = index; at <= last; at++) {
        char c = source.charAt(at);
        masked.append(c == '\n' || (codeBefore && codeAfter) ? c : ' ');
      }
      index = last + 1;
    }
    return masked.toString();
  }

  private static String canonicalEntry(String entry, int ordinal) {
    try {
      String pathJson = field(entry, "Path-Json");
      String symbolJson = field(entry, "Symbol-Json");
      String startValue = field(entry, "Start-Line");
      String endValue = field(entry, "End-Line");
      String truncatedValue = field(entry, "Truncated");
      String contentDigest = unquoteCode(field(entry, "Content-SHA256"));
      String completeDigest = unquoteCode(field(entry, "Complete-Body-SHA256"));
      String contentJson = field(entry, "Content-Json");
      if (pathJson == null
          || symbolJson == null
          || startValue == null
          || endValue == null
          || contentJson == null
          || !INTEGER.matcher(startValue).matches()
          || !INTEGER.matcher(endValue).matches()
          || (!"true".equals(truncatedValue) && !"false".equals(truncatedValue))
          || contentDigest == null
          || completeDigest == null
          || !DIGEST.matcher(contentDigest).matches()
          || !DIGEST.matcher(completeDigest).matches()) return null;
      String path = JSON.readValue(pathJson, String.class);
      String symbol = JSON.readValue(symbolJson, String.class);
      String content = JSON.readValue(contentJson, String.class);
      int startLine = Integer.parseInt(startValue);
      int endLine = Integer.parseInt(endValue);
      boolean valid =
          path != null
              && !path.isBlank()
              && SOURCE_PATH.matcher(path).matches()
              && symbol != null
              && !symbol.isBlank()
              && symbol.matches("[A-Za-z_$][A-Za-z0-9_$]*")
              && startLine > 0
              && endLine == startLine + countNewlines(content)
              && content.length() <= MAX_CHARS_PER_SYMBOL
              && contentDigest.equals(sha256(content))
              && ("true".equals(truncatedValue) || completeDigest.equals(contentDigest));
      if (!valid) return null;
      return "\n### Exported function "
          + ordinal
          + "\n\n- Path-Json: "
          + pathJson
          + "\n- Symbol-Json: "
          + symbolJson
          + "\n- Start-Line: "
          + startValue
          + "\n- End-Line: "
          + endValue
          + "\n- Truncated: "
          + truncatedValue
          + "\n- Content-SHA256: `"
          + contentDigest
          + "`\n- Complete-Body-SHA256: `"
          + completeDigest
          + "`\n- Content-Json: "
          + contentJson
          + "\n";
    } catch (Exception malformed) {
      return null;
    }
  }

  private static String header(String repository, String sourceRevision) {
    return BEGIN
        + "\n## Deterministic exported-function evidence\n\n"
        + "- Evidence-Format: `EXECUTESPEC_EXPORTED_FUNCTION_EVIDENCE_V1`\n"
        + "- Repository-Json: "
        + jsonString(repository)
        + "\n- Source-Revision: `"
        + sourceRevision.toLowerCase(java.util.Locale.ROOT)
        + "`\n";
  }

  private static String field(String block, String name) {
    String prefix = "- " + name + ": ";
    int start = block.indexOf(prefix);
    if (start < 0 || block.indexOf(prefix, start + prefix.length()) >= 0) return null;
    int end = block.indexOf('\n', start + prefix.length());
    return block.substring(start + prefix.length(), end < 0 ? block.length() : end).trim();
  }

  private static String unquoteCode(String value) {
    return value != null && value.length() >= 2 && value.startsWith("`") && value.endsWith("`")
        ? value.substring(1, value.length() - 1)
        : null;
  }

  static String stripBlocks(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    String remaining = markdown;
    while (true) {
      int begin = remaining.indexOf(BEGIN);
      if (begin < 0) return remaining;
      int end = remaining.indexOf(END, begin + BEGIN.length());
      if (end < 0) return remaining.substring(0, begin);
      remaining = remaining.substring(0, begin) + remaining.substring(end + END.length());
    }
  }

  static String render(String repository, String sourceRevision, Map<String, String> sourceByPath) {
    if (repository == null
        || repository.isBlank()
        || sourceRevision == null
        || !EXACT_SHA.matcher(sourceRevision).matches()
        || sourceByPath == null
        || sourceByPath.isEmpty()) {
      return "";
    }

    String header = header(repository, sourceRevision);
    List<FunctionBody> candidates = new ArrayList<>();
    sourceByPath.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .filter(source -> SOURCE_PATH.matcher(source.getKey()).matches())
        .forEach(source -> candidates.addAll(extract(source.getKey(), source.getValue())));

    StringBuilder block = new StringBuilder(header);
    int emitted = 0;
    for (FunctionBody function : candidates) {
      if (emitted >= MAX_SYMBOLS) return finish(block, emitted, true);
      String entry = renderEntry(function, emitted + 1);
      if (block.length() + entry.length() + finishSuffix(emitted + 1, true).length()
          > MAX_BLOCK_CHARS) {
        return emitted == 0 ? "" : finish(block, emitted, true);
      }
      block.append(entry);
      emitted++;
    }
    return emitted == 0 ? "" : finish(block, emitted, false);
  }

  private static String finish(StringBuilder block, int emitted, boolean repositoryTruncated) {
    block.append(finishSuffix(emitted, repositoryTruncated));
    return block.toString();
  }

  private static String finishSuffix(int emitted, boolean repositoryTruncated) {
    return "\n- Emitted-Symbols: "
        + emitted
        + "\n- Repository-Evidence-Truncated: "
        + repositoryTruncated
        + "\n"
        + END
        + "\n\n";
  }

  private static String renderEntry(FunctionBody function, int ordinal) {
    String body = function.body();
    int excerptEnd = boundedEnd(body, MAX_CHARS_PER_SYMBOL);
    String excerpt = body.substring(0, excerptEnd);
    boolean truncated = excerptEnd < body.length();
    int endLine = function.startLine() + countNewlines(excerpt);
    return "\n### Exported function "
        + ordinal
        + "\n\n"
        + "- Path-Json: "
        + jsonString(function.path())
        + "\n- Symbol-Json: "
        + jsonString(function.symbol())
        + "\n- Start-Line: "
        + function.startLine()
        + "\n- End-Line: "
        + endLine
        + "\n- Truncated: "
        + truncated
        + "\n- Content-SHA256: `"
        + sha256(excerpt)
        + "`\n- Complete-Body-SHA256: `"
        + sha256(body)
        + "`\n- Content-Json: "
        + jsonString(excerpt)
        + "\n";
  }

  static List<FunctionBody> extract(String path, String source) {
    if (path == null
        || source == null
        || source.isBlank()
        || !SOURCE_PATH.matcher(path).matches()
        || containsAmbiguousCodeSlash(source)) return List.of();
    List<FunctionBody> functions = new ArrayList<>();
    Matcher declarations = EXPORTED_NAMED_FUNCTION.matcher(source);
    while (declarations.find()) {
      if (!isCodeOffset(source, declarations.start())) continue;
      int bodyStart = findBodyStart(source, declarations.end());
      if (bodyStart < 0) continue;
      int bodyEnd = matchingBraceEnd(source, bodyStart);
      if (bodyEnd < 0) continue;
      int declarationStart = declarations.start();
      int lineStart = source.lastIndexOf('\n', declarationStart - 1) + 1;
      functions.add(
          new FunctionBody(
              path,
              declarations.group(1),
              1 + countNewlines(source.substring(0, lineStart)),
              source.substring(lineStart, bodyEnd)));
    }
    return List.copyOf(functions);
  }

  private static boolean isCodeOffset(String source, int offset) {
    ScanState state = new ScanState();
    for (int i = 0; i < offset; i++) i = state.consume(source, i);
    return state.code();
  }

  private static int findBodyStart(String source, int from) {
    int closeParameters = findClosingParenthesis(source, from - 1);
    if (closeParameters < 0) return -1;
    int bodyStart = skipWhitespace(source, closeParameters + 1);
    // Explicit TypeScript return types are intentionally outside this first exact-evidence slice.
    // Requiring an immediate body opener avoids mistaking object/generic return-type braces for
    // implementation source.
    return bodyStart < source.length() && source.charAt(bodyStart) == '{' ? bodyStart : -1;
  }

  /**
   * Slash has context-sensitive regex-vs-division meaning in JavaScript. Until this bounded lexer
   * models that grammar, omit the whole file when a non-comment slash appears in code rather than
   * risk treating regex braces or parentheses as structure.
   */
  private static boolean containsAmbiguousCodeSlash(String source) {
    ScanState state = new ScanState();
    for (int i = 0; i < source.length(); i++) {
      int consumed = state.consume(source, i);
      if (consumed != i) {
        i = consumed;
        continue;
      }
      if (state.code() && source.charAt(i) == '/') return true;
    }
    return false;
  }

  private static int findClosingParenthesis(String source, int opening) {
    int depth = 0;
    ScanState state = new ScanState();
    for (int i = opening; i < source.length(); i++) {
      i = state.consume(source, i);
      if (!state.code()) continue;
      char c = source.charAt(i);
      if (c == '(') depth++;
      if (c == ')' && --depth == 0) return i;
    }
    return -1;
  }

  private static int matchingBraceEnd(String source, int opening) {
    int depth = 0;
    ScanState state = new ScanState();
    for (int i = opening; i < source.length(); i++) {
      i = state.consume(source, i);
      if (!state.code()) continue;
      char c = source.charAt(i);
      if (c == '{') depth++;
      if (c == '}' && --depth == 0) return i + 1;
    }
    return -1;
  }

  private static int skipWhitespace(String source, int from) {
    int cursor = from;
    while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
    return cursor;
  }

  private static int boundedEnd(String value, int limit) {
    if (value.length() <= limit) return value.length();
    int end = limit;
    if (Character.isHighSurrogate(value.charAt(end - 1))
        && end < value.length()
        && Character.isLowSurrogate(value.charAt(end))) end--;
    return end;
  }

  private static int countNewlines(String value) {
    int count = 0;
    for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') count++;
    return count;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  /** JSON-string encoding is exact/reversible and prevents source text from injecting markers. */
  private static String jsonString(String value) {
    StringBuilder encoded = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> encoded.append("\\\"");
        case '\\' -> encoded.append("\\\\");
        case '\b' -> encoded.append("\\b");
        case '\f' -> encoded.append("\\f");
        case '\n' -> encoded.append("\\n");
        case '\r' -> encoded.append("\\r");
        case '\t' -> encoded.append("\\t");
        case '<' -> encoded.append("\\u003c");
        case '>' -> encoded.append("\\u003e");
        default -> {
          if (c < 0x20) encoded.append(String.format("\\u%04x", (int) c));
          else encoded.append(c);
        }
      }
    }
    return encoded.append('"').toString();
  }

  record FunctionBody(String path, String symbol, int startLine, String body) {}

  enum InspectionStatus {
    ABSENT,
    VALID,
    MALFORMED
  }

  record Inspection(InspectionStatus status, String block) {
    static Inspection absent() {
      return new Inspection(InspectionStatus.ABSENT, null);
    }

    static Inspection valid(String block) {
      return new Inspection(InspectionStatus.VALID, block);
    }

    static Inspection malformed() {
      return new Inspection(InspectionStatus.MALFORMED, null);
    }
  }

  /** Minimal lexical state for braces/parentheses inside JS strings and comments. */
  private static final class ScanState {
    private char quote;
    private boolean lineComment;
    private boolean blockComment;
    private boolean escaped;

    int consume(String source, int index) {
      char c = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
      if (lineComment) {
        if (c == '\n') lineComment = false;
        return index;
      }
      if (blockComment) {
        if (c == '*' && next == '/') {
          blockComment = false;
          return index + 1;
        }
        return index;
      }
      if (quote != 0) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == quote) quote = 0;
        return index;
      }
      if (c == '/' && next == '/') {
        lineComment = true;
        return index + 1;
      }
      if (c == '/' && next == '*') {
        blockComment = true;
        return index + 1;
      }
      if (c == '\'' || c == '"' || c == '`') quote = c;
      return index;
    }

    boolean code() {
      return quote == 0 && !lineComment && !blockComment;
    }
  }
}
