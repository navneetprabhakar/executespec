package com.navneet.executespec.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Fail-closed detector for high-confidence credentials in agent-generated file content. It avoids
 * generic {@code password=} matching (too many legitimate fixtures) and blocks only provider/token
 * formats with strong prefixes or PEM private-key material. Matched values are never returned or
 * logged.
 */
@Component
public class GeneratedSecretScanner {

  private static final List<Rule> RULES =
      List.of(
          rule("PRIVATE_KEY", "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
          rule("ANTHROPIC_KEY", "\\bsk-ant-[A-Za-z0-9_-]{30,}\\b"),
          rule("STRIPE_LIVE_KEY", "\\bsk_live_[A-Za-z0-9]{20,}\\b"),
          rule("OPENAI_OR_DEEPSEEK_KEY", "\\bsk-(?:proj-)?[A-Za-z0-9_-]{30,}\\b"),
          rule("GOOGLE_KEY", "\\bAIza[A-Za-z0-9_-]{30,}\\b"),
          rule("GITHUB_TOKEN", "\\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\\b"),
          rule("EXECUTESPEC_PAT", "\\bes_pat_[A-Za-z0-9_-]{24,}\\b"),
          rule("AWS_ACCESS_KEY", "\\bAKIA[0-9A-Z]{16}\\b"));

  private static final List<String> EXAMPLE_MARKERS =
      List.of("example", "placeholder", "replace_me", "changeme", "your_key", "dummy");

  public Optional<Finding> scan(String content) {
    if (content == null || content.isBlank()) return Optional.empty();
    for (Rule rule : RULES) {
      Matcher matcher = rule.pattern().matcher(content);
      while (matcher.find()) {
        String normalized = matcher.group().toLowerCase(Locale.ROOT);
        boolean explicitExample = EXAMPLE_MARKERS.stream().anyMatch(normalized::contains);
        if (!explicitExample) return Optional.of(new Finding(rule.category()));
      }
    }
    return Optional.empty();
  }

  /**
   * Redact every high-confidence credential recognized by this scanner.
   *
   * <p>This is deliberately backed by the exact same rules as {@link #scan(String)} so an export
   * cannot drift into a weaker, independently-maintained credential pattern set. Category names are
   * retained for operator diagnostics; matched values are never returned.
   */
  public String redactRecognizedSecrets(String content) {
    if (content == null || content.isBlank()) return content;
    String redacted = content;
    for (Rule rule : RULES) {
      Matcher matcher = rule.pattern().matcher(redacted);
      StringBuffer out = new StringBuffer(redacted.length());
      while (matcher.find()) {
        String normalized = matcher.group().toLowerCase(Locale.ROOT);
        boolean explicitExample = EXAMPLE_MARKERS.stream().anyMatch(normalized::contains);
        matcher.appendReplacement(
            out,
            Matcher.quoteReplacement(
                explicitExample ? matcher.group() : "[REDACTED_" + rule.category() + "]"));
      }
      matcher.appendTail(out);
      redacted = out.toString();
    }
    return redacted;
  }

  /**
   * Streams an artifact through the same high-confidence rules without loading a potentially large
   * repository file into heap. Rules are ASCII, so ISO-8859-1 preserves every candidate byte; the
   * overlap catches a credential split across read boundaries.
   */
  public Optional<Finding> scan(InputStream input) throws IOException {
    if (input == null) return Optional.empty();
    byte[] buffer = new byte[64 * 1024];
    String overlap = "";
    int read;
    while ((read = input.read(buffer)) != -1) {
      String chunk = overlap + new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
      Optional<Finding> finding = scan(chunk);
      if (finding.isPresent()) return finding;
      overlap = chunk.substring(Math.max(0, chunk.length() - 512));
    }
    return Optional.empty();
  }

  private static Rule rule(String category, String regex) {
    return new Rule(category, Pattern.compile(regex));
  }

  private record Rule(String category, Pattern pattern) {}

  public record Finding(String category) {}
}
