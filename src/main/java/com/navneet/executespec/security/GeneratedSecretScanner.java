package com.navneet.executespec.security;

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

  private static Rule rule(String category, String regex) {
    return new Rule(category, Pattern.compile(regex));
  }

  private record Rule(String category, Pattern pattern) {}

  public record Finding(String category) {}
}
