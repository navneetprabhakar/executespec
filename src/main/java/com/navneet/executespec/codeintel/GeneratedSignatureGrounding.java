package com.navneet.executespec.codeintel;

import java.text.BreakIterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes unverifiable concrete method contracts from provider-generated Markdown.
 *
 * <p>A method name remains useful navigation evidence, but parameter types, arity, and container
 * types are source contracts. Inline-code claims are therefore retained verbatim only when their
 * whitespace-normalized signature occurs in the codemap/source evidence; otherwise they are
 * downgraded to the qualified method name.
 */
final class GeneratedSignatureGrounding {

  private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\r\\n]+)`");
  private static final Pattern METHOD_CLAIM =
      Pattern.compile(
          "^([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*\\(([^()]*)\\)$");
  private static final Pattern CODEMAP_SYMBOL =
      Pattern.compile(
          "(?m)^-\\s+`(?:(?:class|interface|record|enum|def|func|fn)\\s+)?"
              + "([A-Za-z_$][A-Za-z0-9_$]*)`\\s*$");
  private static final Pattern CALL_EDGE_LANGUAGE =
      Pattern.compile(
          "(?i)\\b(?:calls?|invokes?|delegates\\s+to|dispatches\\s+to|routes\\s+to|"
              + "works\\s+with|interacts\\s+with|collaborates\\s+with|collaboration|"
              + "depends\\s+on|uses?|exposed\\s+by)\\b");
  private static final Pattern ACTIVITY_LANGUAGE =
      Pattern.compile(
          "(?i)\\b(?:inactive|unused|dead\\s+code|stub(?:bed)?|unwired|not\\s+wired|"
              + "not\\s+called|never\\s+called|not\\s+used|never\\s+used|"
              + "not\\s+invoked|never\\s+invoked|unreachable|"
              + "(?:not|isn't|is\\s+not|wasn't|was\\s+not)\\s+(?:fully\\s+)?integrated|"
              + "partially\\s+integrated)\\b");
  private static final Pattern SINGLE_SYMBOL_RELATION_LANGUAGE =
      Pattern.compile(
          "(?i)\\b(?:rel(?:y|ies|ied|ying)\\s+on|" + "hint(?:ed|s|ing)?\\s+at(?:\\s+by)?)\\b");
  private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```.*?(?:```|\\z)");
  private static final Pattern MERMAID_BLOCK =
      Pattern.compile("(?is)```mermaid\\s*(.*?)(?:```|\\z)");
  private static final Pattern DIRECTIONAL_MERMAID_EDGE = Pattern.compile("-->|->>");
  private static final String SPECULATIVE_NOUN_LABEL = "(?:hinted|placeholder|prototype|candidate)";
  private static final String EVIDENCE_LIMIT =
      "codemap evidence does not establish runtime relationships or liveness";
  private static final String DIAGRAM_OMITTED =
      "> Directional diagram omitted: " + EVIDENCE_LIMIT + ".";

  private GeneratedSignatureGrounding() {}

  static String sanitize(String markdown, String sourceEvidence) {
    if (markdown == null || markdown.isEmpty()) return markdown;
    String normalizedEvidence = normalize(sourceEvidence);
    Matcher inline = INLINE_CODE.matcher(markdown);
    StringBuffer grounded = new StringBuffer(markdown.length());
    while (inline.find()) {
      String claim = inline.group(1).trim();
      Matcher method = METHOD_CLAIM.matcher(claim);
      if (!method.matches() || verified(method.group(1), method.group(2), normalizedEvidence)) {
        inline.appendReplacement(grounded, Matcher.quoteReplacement(inline.group()));
        continue;
      }
      inline.appendReplacement(grounded, Matcher.quoteReplacement("`" + method.group(1) + "`"));
    }
    inline.appendTail(grounded);
    return grounded.toString();
  }

  /**
   * Project-document prompts contain both generated architecture and codemaps. Only the codemap
   * tail is admissible grounding evidence, otherwise a hallucination in an older architecture
   * version could validate the same hallucination in a generated document.
   */
  static String codemapEvidence(String sourceBundle) {
    if (sourceBundle == null) return "";
    int start = sourceBundle.indexOf("# Repository codemap:");
    return start < 0 ? "" : sourceBundle.substring(start);
  }

  /**
   * Finds high-confidence behavioral claims that declaration-only codemaps cannot prove.
   *
   * <p>Generated architecture prose is deliberately excluded from the evidence passed here: one
   * generated document cannot validate the same hallucination in another. The check is narrow and
   * fires only when a relationship sentence names at least two codemap symbols, or an
   * activity/liveness sentence names at least one.
   */
  static Optional<String> unsupportedBehaviorClaim(String markdown, String sourceEvidence) {
    if (markdown == null || markdown.isBlank()) return Optional.empty();
    Matcher mermaid = MERMAID_BLOCK.matcher(markdown);
    while (mermaid.find()) {
      if (DIRECTIONAL_MERMAID_EDGE.matcher(mermaid.group(1)).find()) {
        return Optional.of(
            "unsupported directional Mermaid edge; declaration-only codemap evidence does not"
                + " establish component call or flow direction");
      }
    }

    Set<String> symbols = codemapSymbols(sourceEvidence);
    if (symbols.isEmpty()) return Optional.empty();
    String prose = FENCED_BLOCK.matcher(markdown).replaceAll(" ");
    for (String sentence : prose.split("(?<=[.!?])\\s+|\\R+")) {
      Optional<BehaviorClaim> claim = behaviorClaim(sentence, symbols);
      if (claim.isPresent()) return Optional.of(claim.orElseThrow().reason());
    }
    return Optional.empty();
  }

  /**
   * Deterministically degrades only unsupported behavior prose while preserving headings and the
   * rest of the generated document byte-for-byte.
   */
  static String sanitizeUnsupportedBehavior(String markdown, String sourceEvidence) {
    if (markdown == null || markdown.isEmpty()) return markdown;
    String withoutDirectionalDiagrams = removeDirectionalMermaid(markdown);
    Set<String> symbols = codemapSymbols(sourceEvidence);
    if (symbols.isEmpty()) return withoutDirectionalDiagrams;

    Matcher fenced = FENCED_BLOCK.matcher(withoutDirectionalDiagrams);
    StringBuilder sanitized = new StringBuilder(withoutDirectionalDiagrams.length());
    int cursor = 0;
    while (fenced.find()) {
      sanitized.append(
          sanitizeProse(withoutDirectionalDiagrams.substring(cursor, fenced.start()), symbols));
      sanitized.append(fenced.group());
      cursor = fenced.end();
    }
    sanitized.append(sanitizeProse(withoutDirectionalDiagrams.substring(cursor), symbols));
    return sanitized.toString();
  }

  private static String removeDirectionalMermaid(String markdown) {
    Matcher mermaid = MERMAID_BLOCK.matcher(markdown);
    StringBuffer sanitized = new StringBuffer(markdown.length());
    while (mermaid.find()) {
      String replacement =
          DIRECTIONAL_MERMAID_EDGE.matcher(mermaid.group(1)).find()
              ? DIAGRAM_OMITTED
              : mermaid.group();
      mermaid.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
    }
    mermaid.appendTail(sanitized);
    return sanitized.toString();
  }

  private static String sanitizeProse(String prose, Set<String> symbols) {
    StringBuilder sanitized = new StringBuilder(prose.length());
    int cursor = 0;
    while (cursor < prose.length()) {
      int newline = prose.indexOf('\n', cursor);
      int end = newline < 0 ? prose.length() : newline + 1;
      sanitized.append(sanitizeProseLine(prose.substring(cursor, end), symbols));
      cursor = end;
    }
    return sanitized.toString();
  }

  private static String sanitizeProseLine(String prose, Set<String> symbols) {
    BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
    sentences.setText(prose);
    StringBuilder sanitized = new StringBuilder(prose.length());
    int start = sentences.first();
    for (int end = sentences.next();
        end != BreakIterator.DONE;
        start = end, end = sentences.next()) {
      String sentence = prose.substring(start, end);
      Optional<BehaviorClaim> claim = behaviorClaim(sentence, symbols);
      if (claim.isEmpty()) {
        sanitized.append(sentence);
        continue;
      }
      int leading = 0;
      while (leading < sentence.length() && Character.isWhitespace(sentence.charAt(leading))) {
        leading++;
      }
      int trailing = sentence.length();
      while (trailing > leading && Character.isWhitespace(sentence.charAt(trailing - 1))) {
        trailing--;
      }
      sanitized.append(sentence, 0, leading);
      sanitized.append(declarationOnlyNote(claim.orElseThrow().symbols()));
      sanitized.append(sentence, trailing, sentence.length());
    }
    return sanitized.toString();
  }

  private static Optional<BehaviorClaim> behaviorClaim(String sentence, Set<String> symbols) {
    if (sentence.contains("Declared codemap symbols:") && sentence.contains(EVIDENCE_LIMIT)) {
      return Optional.empty();
    }
    Set<String> mentioned = mentionedSymbols(sentence, symbols);
    if (mentioned.size() >= 2 && CALL_EDGE_LANGUAGE.matcher(sentence).find()) {
      return Optional.of(
          new BehaviorClaim(
              "unsupported call-edge claim between codemap symbols "
                  + String.join(", ", mentioned.stream().limit(3).toList()),
              List.copyOf(mentioned)));
    }
    if (!mentioned.isEmpty() && SINGLE_SYMBOL_RELATION_LANGUAGE.matcher(sentence).find()) {
      return Optional.of(
          new BehaviorClaim(
              "unsupported single-symbol relationship attribution about codemap symbol "
                  + mentioned.iterator().next(),
              List.copyOf(mentioned)));
    }
    if (!mentioned.isEmpty() && ACTIVITY_LANGUAGE.matcher(sentence).find()) {
      return Optional.of(
          new BehaviorClaim(
              "unsupported activity/liveness claim about codemap symbol "
                  + mentioned.iterator().next(),
              List.copyOf(mentioned)));
    }
    Optional<BehaviorClaim> speculative = speculativeLabelClaim(sentence, mentioned);
    if (speculative.isPresent()) return speculative;
    if (mentioned.size() >= 2) {
      return Optional.of(
          new BehaviorClaim(
              "unsupported multi-symbol relationship claim between codemap symbols "
                  + String.join(", ", mentioned.stream().limit(3).toList()),
              List.copyOf(mentioned)));
    }
    return Optional.empty();
  }

  private static Optional<BehaviorClaim> speculativeLabelClaim(
      String sentence, Set<String> mentionedSymbols) {
    String plainSentence = sentence.replace("`", "");
    for (String symbol : mentionedSymbols) {
      String quoted = Pattern.quote(symbol);
      Pattern labelNearSymbol =
          Pattern.compile(
              "(?i)(?:"
                  + SPECULATIVE_NOUN_LABEL
                  + "\\s+(?:(?:method|function|hook|mechanism)\\s+)?"
                  + quoted
                  + "|"
                  + quoted
                  + "\\s+(?:(?:is|remains|appears|looks|seems)(?:\\s+to\\s+be)?"
                  + "\\s+(?:an?\\s+)?"
                  + SPECULATIVE_NOUN_LABEL
                  + "|hints?\\s+at)"
                  + ")");
      if (labelNearSymbol.matcher(plainSentence).find()) {
        return Optional.of(
            new BehaviorClaim(
                "unsupported speculative activity/maturity label about codemap symbol " + symbol,
                List.copyOf(mentionedSymbols)));
      }
    }
    return Optional.empty();
  }

  private static String declarationOnlyNote(List<String> symbols) {
    String declared =
        symbols.stream()
            .limit(6)
            .map(symbol -> "`" + symbol + "`")
            .reduce((a, b) -> a + ", " + b)
            .orElse("the referenced symbols");
    return "Declared codemap symbols: " + declared + "; " + EVIDENCE_LIMIT + ".";
  }

  private static Set<String> codemapSymbols(String evidence) {
    Set<String> symbols = new LinkedHashSet<>();
    if (evidence == null || evidence.isBlank()) return symbols;
    Matcher matcher = CODEMAP_SYMBOL.matcher(evidence);
    while (matcher.find()) {
      symbols.add(matcher.group(1));
    }
    return symbols;
  }

  private static Set<String> mentionedSymbols(String sentence, Set<String> symbols) {
    Set<String> mentioned = new LinkedHashSet<>();
    String lower = sentence.toLowerCase(Locale.ROOT);
    for (String symbol : symbols) {
      Pattern occurrence =
          Pattern.compile(
              "(?<![A-Za-z0-9_$])"
                  + Pattern.quote(symbol.toLowerCase(Locale.ROOT))
                  + "(?![A-Za-z0-9_$])");
      if (occurrence.matcher(lower).find()) mentioned.add(symbol);
    }
    return mentioned;
  }

  private static boolean verified(String qualifiedName, String parameters, String evidence) {
    if (evidence.isEmpty()) return false;
    String exact = normalize(qualifiedName + "(" + parameters + ")");
    if (evidence.contains(exact)) return true;
    int dot = qualifiedName.lastIndexOf('.');
    String simpleName = dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    return evidence.contains(normalize(simpleName + "(" + parameters + ")"));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "");
  }

  private record BehaviorClaim(String reason, List<String> symbols) {}
}
