package com.navneet.executespec.auth.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpMethod;

/**
 * Enforceable capability policy for programmatic Personal Access Tokens.
 *
 * <p>Existing {@code full} tokens remain compatible. New clients default to {@link
 * #CLIENT_DEFAULT_SCOPES}, which deliberately excludes billing, token administration, operator test
 * routes and global administration. Unknown mutating routes fail closed; reads require the explicit
 * {@code api:read} capability.
 */
public final class PatScopePolicy {

  public static final String FULL = "full";
  public static final String API_READ = "api:read";
  public static final String RUNS_WRITE = "runs:write";
  public static final String PROJECTS_WRITE = "projects:write";
  public static final String AGENT_WRITE = "agent:write";
  public static final String SETTINGS_WRITE = "settings:write";
  public static final String CREDENTIALS_MANAGE = "credentials:manage";
  public static final String BILLING_WRITE = "billing:write";
  public static final String TOKENS_MANAGE = "tokens:manage";
  public static final String ADMIN = "admin";

  public static final List<String> CLIENT_DEFAULT_SCOPES =
      List.of(API_READ, RUNS_WRITE, PROJECTS_WRITE, AGENT_WRITE, SETTINGS_WRITE);

  private static final Set<String> ALLOWED =
      Set.of(
          FULL,
          API_READ,
          RUNS_WRITE,
          PROJECTS_WRITE,
          AGENT_WRITE,
          SETTINGS_WRITE,
          CREDENTIALS_MANAGE,
          BILLING_WRITE,
          TOKENS_MANAGE,
          ADMIN);

  private PatScopePolicy() {}

  public static String normalizeRequested(List<String> requested) {
    List<String> source =
        requested == null || requested.isEmpty() ? CLIENT_DEFAULT_SCOPES : requested;
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String raw : source) {
      if (raw == null || raw.isBlank()) continue;
      String scope = raw.strip().toLowerCase(Locale.ROOT);
      if (!ALLOWED.contains(scope)) {
        throw new IllegalArgumentException("Unsupported PAT scope: " + scope);
      }
      normalized.add(scope);
    }
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("At least one PAT scope is required");
    }
    if (normalized.contains(FULL) && normalized.size() > 1) {
      throw new IllegalArgumentException("PAT scope 'full' cannot be combined with other scopes");
    }
    return String.join(",", normalized);
  }

  public static boolean allows(String encodedScopes, String method, String requestUri) {
    Set<String> scopes = parse(encodedScopes);
    if (scopes.contains(FULL)) return true;
    String required = requiredScope(method, requestUri);
    return required != null && scopes.contains(required);
  }

  static String requiredScope(String method, String requestUri) {
    String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
    String path = requestUri == null ? "" : requestUri;
    // Privileged namespaces are capability-gated for both reads and writes. Do this before the
    // generic read rule or api:read would unintentionally become operator/token-admin access.
    if (path.startsWith("/api/admin/") || path.startsWith("/api/v2/test/")) return ADMIN;
    if (path.startsWith("/api/auth/me/tokens")) return TOKENS_MANAGE;
    if (HttpMethod.GET.matches(verb)
        || HttpMethod.HEAD.matches(verb)
        || HttpMethod.OPTIONS.matches(verb)) {
      return API_READ;
    }
    if (path.startsWith("/api/billing/") || path.startsWith("/api/webhooks/")) {
      return BILLING_WRITE;
    }
    if (path.startsWith("/api/auth/me/settings/ai-keys")
        || path.startsWith("/api/auth/me/settings/github")
        || path.startsWith("/api/auth/me/settings/bitbucket")
        || path.startsWith("/api/auth/me/settings/jira")
        || path.startsWith("/api/auth/me/settings/confluence")
        || path.startsWith("/api/org/me/settings/ai-keys")
        || path.startsWith("/api/org/me/settings/key-source")
        || path.startsWith("/api/org/me/settings/jira")
        || path.startsWith("/api/org/me/settings/confluence")) {
      return CREDENTIALS_MANAGE;
    }
    if (path.startsWith("/api/agent/")
        || path.startsWith("/api/orchestrator/")
        || path.startsWith("/api/spec/chat")) return AGENT_WRITE;
    if (path.startsWith("/api/runs/")
        || path.equals("/api/runs")
        || path.startsWith("/api/executions/")) return RUNS_WRITE;
    if (path.startsWith("/api/projects/")
        || path.equals("/api/projects")
        || path.startsWith("/api/repos/")
        || path.startsWith("/api/pr-reviews")
        || path.startsWith("/api/code-reviews")
        || path.startsWith("/api/deployments")) return PROJECTS_WRITE;
    if (path.startsWith("/api/auth/me/")
        || path.startsWith("/api/org/")
        || path.equals("/api/org")
        || path.startsWith("/api/notifications/")) return SETTINGS_WRITE;
    return null;
  }

  private static Set<String> parse(String encodedScopes) {
    if (encodedScopes == null || encodedScopes.isBlank()) return Set.of();
    return Arrays.stream(encodedScopes.split(","))
        .map(String::strip)
        .map(s -> s.toLowerCase(Locale.ROOT))
        .filter(s -> !s.isEmpty())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
