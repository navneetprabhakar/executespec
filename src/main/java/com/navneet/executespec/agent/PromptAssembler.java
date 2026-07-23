package com.navneet.executespec.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles LLM prompt files by resolving {@code {{include:fragment-name}}} markers and
 * substituting runtime variables. Fragment files live in {@code classpath:prompts/fragments/} and
 * provide a single source of truth for shared rules that are used across multiple provider-specific
 * prompt files.
 *
 * <p>Include syntax:
 *
 * <ul>
 *   <li>{@code {{include:fragment-name}}} — loads {@code fragments/fragment-name.md}
 *   <li>{@code {{include:fragment-name:role}}} — loads base fragment plus optional {@code
 *       fragments/fragment-name-role.md} appended after
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromptAssembler {

  private static final Pattern INCLUDE_PATTERN =
      Pattern.compile("\\{\\{include:([a-z-]+)(?::([a-z-]+))?\\}\\}");

  /**
   * RR-1230 — matches a single {@code {{VAR}}} placeholder token. Variable substitution scans the
   * template for these tokens exactly once and replaces each from the supplied map, so an inserted
   * value that happens to contain another {@code {{MARKER}}} is NEVER rescanned and rewritten by a
   * later map entry. Every declared prompt variable is uppercase/digit/underscore.
   */
  private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

  private static final String PROMPTS_BASE = "classpath:prompts/";

  private final ResourceLoader resourceLoader;
  private final PromptHashAuditRepository promptAuditRepository;

  /** Nullable in unit tests that construct the assembler without a metrics backend. */
  private final MeterRegistry meterRegistry;

  /**
   * Returned by {@link #assembleAndTrack}.
   *
   * @param text the assembled prompt
   * @param hash 12-char SHA-256 prefix of {@link #text}
   * @param tracked {@code true} when the prompt-audit row was durably persisted; {@code false} when
   *     the audit write failed, so downstream must NOT treat this prompt's provenance as reliably
   *     recorded (RR-521 — the audit linkage is the only proof a run's prompt was tracked).
   */
  public record AssembledPrompt(String text, String hash, boolean tracked) {}

  @Value("${prompts.base-path:classpath:prompts}")
  private String basePath;

  /** Assemble a prompt file, resolving all include markers. No variable substitution. */
  public String assemble(String promptPath) {
    return assemble(promptPath, Map.of());
  }

  /**
   * Assemble a prompt file, resolving all include markers and then substituting the provided
   * variables (format: {@code {{KEY}}} → value).
   */
  public String assemble(String promptPath, Map<String, String> variables) {
    String template = loadFile(promptPath);
    template = resolveIncludes(template);
    return substituteVariables(template, variables);
  }

  /**
   * RR-1230 — single-pass variable substitution. The template is scanned once for {@code {{VAR}}}
   * tokens; each token found in the ORIGINAL template is replaced by its mapped value. Because
   * replacements are written into a fresh buffer via {@link Matcher#appendReplacement}, a value
   * that itself contains a {@code {{MARKER}}} substring is emitted verbatim and never re-matched,
   * so a content-controlled marker (customer spec, repository text, memory, tool/history) can no
   * longer duplicate or mutate another input, amplify prompt size, or break cache/audit
   * byte-stability. The result is independent of {@code Map} iteration order. Unknown tokens (no
   * map entry) are left literal, preserving the prior behaviour for prompts with unfilled
   * placeholders.
   */
  private static String substituteVariables(String template, Map<String, String> variables) {
    if (variables == null || variables.isEmpty()) {
      return template;
    }
    Matcher matcher = VARIABLE_PATTERN.matcher(template);
    StringBuilder sb = new StringBuilder(template.length());
    while (matcher.find()) {
      String key = matcher.group(1);
      String value = variables.get(key);
      if (value == null) {
        // Not a supplied variable — keep the token literal (unfilled placeholder).
        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
      } else {
        matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
      }
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  private String resolveIncludes(String template) {
    Matcher matcher = INCLUDE_PATTERN.matcher(template);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String name = matcher.group(1);
      String role = matcher.group(2);
      String fragment = loadFile("fragments/" + name + ".md");
      if (role != null) {
        String roleAddition = loadFileSafe("fragments/" + name + "-" + role + ".md");
        if (roleAddition != null) {
          fragment = fragment + "\n\n" + roleAddition;
        }
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(fragment));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  /**
   * Assemble and record a {@link PromptAudit} row for tracking. Returns the assembled text together
   * with its 12-char SHA-256 prefix hash.
   */
  public AssembledPrompt assembleAndTrack(
      String promptPath, Map<String, String> variables, UUID runId, String agentType) {
    String text = assemble(promptPath, variables);
    String hash = sha256Prefix(text, 12);
    try {
      promptAuditRepository.save(
          PromptAudit.builder()
              .runId(runId != null ? runId.toString() : null)
              .agentType(agentType)
              .promptHash(hash)
              .charCount(text.length())
              .build());
      return new AssembledPrompt(text, hash, true);
    } catch (Exception e) {
      // RR-521 — the audit write is the ONLY durable prompt-version linkage. Swallowing this
      // failure
      // silently made a run appear auditable when its prompt provenance had actually vanished.
      // Execution still proceeds (the audit is non-blocking), but the failure is now surfaced as a
      // structured log + metric and the prompt is returned DEGRADED (tracked=false) so downstream
      // does not treat unrecorded provenance as clean.
      log.warn(
          "Prompt-audit persistence failed — prompt provenance NOT recorded (runId={},"
              + " agentType={}, promptHash={}, error={}: {})",
          runId,
          agentType,
          hash,
          e.getClass().getSimpleName(),
          e.getMessage());
      if (meterRegistry != null) {
        Counter.builder("executespec.prompt.audit.failures")
            .description("Prompt-audit rows that failed to persist (RR-521 degraded provenance)")
            .tag("agent_type", agentType == null ? "unknown" : agentType)
            .register(meterRegistry)
            .increment();
      }
      return new AssembledPrompt(text, hash, false);
    }
  }

  private static String sha256Prefix(String input, int prefixLen) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, prefixLen);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 unavailable", e);
    }
  }

  /** Load a file relative to the prompts base path. Throws if not found. */
  public String loadFile(String rel) {
    Resource resource = resourceLoader.getResource(PROMPTS_BASE + rel);
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Cannot load prompt file: prompts/" + rel, e);
    }
  }

  /** Load a file relative to the prompts base path. Returns {@code null} if not found. */
  public String loadFileSafe(String rel) {
    try {
      return loadFile(rel);
    } catch (Exception e) {
      return null;
    }
  }
}
