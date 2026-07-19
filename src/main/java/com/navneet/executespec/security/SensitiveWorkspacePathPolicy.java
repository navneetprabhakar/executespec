package com.navneet.executespec.security;

import java.util.Locale;
import java.util.Set;

/** Fail-closed names for credential material that must never enter an LLM tool result. */
public final class SensitiveWorkspacePathPolicy {

  private static final Set<String> EXACT_NAMES =
      Set.of(
          ".npmrc",
          ".pypirc",
          ".netrc",
          ".git-credentials",
          "credentials.json",
          "service-account.json",
          "service_account.json",
          "gcloud-service-key.json",
          "id_rsa",
          "id_dsa",
          "id_ecdsa",
          "id_ed25519");

  private static final Set<String> SAFE_TEMPLATE_SUFFIXES =
      Set.of(".example", ".sample", ".template", ".example.json", ".sample.json");

  private static final Set<String> SECRET_EXTENSIONS =
      Set.of(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");

  private SensitiveWorkspacePathPolicy() {}

  public static boolean isSensitive(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) return false;
    String normalized = rawPath.replace('\\', '/').toLowerCase(Locale.ROOT);
    int slash = normalized.lastIndexOf('/');
    String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
    if (isSafeTemplate(name)) return false;
    if (".env".equals(name) || name.startsWith(".env.")) return true;
    if (EXACT_NAMES.contains(name)) return true;
    if (normalized.endsWith("/.docker/config.json")
        || "config.json".equals(name) && normalized.contains(".docker/")) {
      return true;
    }
    return SECRET_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  private static boolean isSafeTemplate(String name) {
    return SAFE_TEMPLATE_SUFFIXES.stream().anyMatch(name::endsWith);
  }
}
