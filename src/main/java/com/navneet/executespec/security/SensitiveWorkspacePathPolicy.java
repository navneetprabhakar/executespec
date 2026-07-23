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
          "id_ed25519",
          // RR-083 (2026-07-22, P1) — common credential stores the small original list omitted.
          ".vault-token",
          "vault-token",
          ".pgpass",
          ".dockercfg",
          "kubeconfig",
          "secrets.yaml",
          "secrets.yml",
          "secrets.json",
          "secret.yaml",
          "secret.yml",
          "secret.json");

  /**
   * RR-083 — directory-aware credential paths. A basename check alone cannot distinguish {@code
   * ~/.aws/credentials} (secret) from an arbitrary file named {@code config}, so these are matched
   * against the normalized full path suffix rather than the bare name.
   */
  private static final Set<String> SENSITIVE_PATH_SUFFIXES =
      Set.of(
          "/.aws/credentials",
          "/.config/gcloud/application_default_credentials.json",
          "/.config/gcloud/credentials.db",
          "/.kube/config",
          "/.ssh/id_rsa",
          "/.docker/config.json");

  private static final Set<String> SAFE_TEMPLATE_SUFFIXES =
      Set.of(".example", ".sample", ".template", ".example.json", ".sample.json");

  private static final Set<String> SECRET_EXTENSIONS =
      Set.of(
          ".pem",
          ".key",
          ".p12",
          ".pfx",
          ".jks",
          ".keystore",
          // RR-083 — Terraform state routinely embeds provider credentials/secrets in plaintext.
          ".tfstate",
          ".tfstate.backup");

  private SensitiveWorkspacePathPolicy() {}

  public static boolean isSensitive(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) return false;
    String normalized = rawPath.replace('\\', '/').toLowerCase(Locale.ROOT);
    int slash = normalized.lastIndexOf('/');
    String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
    if (isSafeTemplate(name)) return false;
    if (".env".equals(name) || name.startsWith(".env.")) return true;
    if (EXACT_NAMES.contains(name)) return true;
    // RR-083 — directory-aware credential stores (matched on the full normalized path).
    String withLeadingSlash = normalized.startsWith("/") ? normalized : "/" + normalized;
    if (SENSITIVE_PATH_SUFFIXES.stream().anyMatch(withLeadingSlash::endsWith)) return true;
    if ("config.json".equals(name) && normalized.contains(".docker/")) return true;
    return SECRET_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  private static boolean isSafeTemplate(String name) {
    return SAFE_TEMPLATE_SUFFIXES.stream().anyMatch(name::endsWith);
  }
}
