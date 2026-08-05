package com.navneet.executespec.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.config.AgentProperties;

import org.springframework.stereotype.Component;

/**
 * Redacts server filesystem paths from customer-facing diagnostics.
 *
 * <p>Durable diagnostics and DTO-projected task fields (failure reasons, goal-doneness items,
 * continuation risks) must never leak the host layout. The known-root pattern covers macOS/Linux
 * user and temp trees plus the literal Linux deploy parent {@code /opt/app}; the instance form
 * additionally redacts the exact configured {@code agent.workspace} root wherever it lives.
 *
 * <p>The path character class deliberately excludes quotes so redaction applied to a raw JSON
 * string cannot swallow a closing quote and corrupt the document.
 */
@Component
public class WorkspacePathRedaction {

  public static final String REPLACEMENT = "[workspace path redacted]";

  private static final String PATH_TAIL = "[^\\s;,\"'`]+";

  private static final Pattern KNOWN_UNIX_ROOTS =
      Pattern.compile(
          "(?i)(?<![A-Za-z0-9])/(?:Users|home|tmp|var/folders|private/tmp|private/var/folders|opt/app)/"
              + PATH_TAIL);

  private static final Pattern KNOWN_WINDOWS_ROOTS =
      Pattern.compile("(?i)\\b[A-Z]:\\\\(?:Users|Temp)\\\\" + PATH_TAIL);

  private final Pattern configuredWorkspaceRoot;

  public WorkspacePathRedaction(AgentProperties agentProperties) {
    this.configuredWorkspaceRoot =
        compileConfiguredRoot(agentProperties == null ? null : agentProperties.getWorkspace());
  }

  private static Pattern compileConfiguredRoot(String workspace) {
    if (workspace == null || workspace.isBlank()) return null;
    String normalized = workspace.trim().replaceAll("/+$", "");
    if (normalized.isEmpty() || "/".equals(normalized)) return null;
    return Pattern.compile(Pattern.quote(normalized) + "[^\\s;,\"'`]*");
  }

  /** Redacts the configured workspace root plus every known host root. Null-safe. */
  public String redact(String text) {
    if (text == null || text.isEmpty()) return text;
    String out = text;
    if (configuredWorkspaceRoot != null) {
      out = configuredWorkspaceRoot.matcher(out).replaceAll(Matcher.quoteReplacement(REPLACEMENT));
    }
    return redactKnownRoots(out, REPLACEMENT);
  }

  /**
   * Static known-root redaction for contexts without configuration access. The replacement label is
   * caller-owned so existing diagnostic wording stays stable.
   */
  public static String redactKnownRoots(String text, String replacement) {
    if (text == null || text.isEmpty()) return text;
    String quoted = Matcher.quoteReplacement(replacement);
    String out = KNOWN_UNIX_ROOTS.matcher(text).replaceAll(quoted);
    return KNOWN_WINDOWS_ROOTS.matcher(out).replaceAll(quoted);
  }
}
