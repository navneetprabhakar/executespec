package com.navneet.executespec.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Fails startup when the HS256 signing secret is unset, weak, a known placeholder, or a value that
 * is known to have been published.
 *
 * <p><b>Why every shared profile, not just prod (RR-858):</b> {@code dev} and {@code benchmark} are
 * internet-reachable deployments serving real accounts and real provider credentials. A forgeable
 * token there is a real compromise, so they get the same boot gate production does. Only {@code
 * local} (and tests) run unguarded, because nothing there is reachable.
 *
 * <p><b>Why a digest deny-list, not just a placeholder marker:</b> the previous check tested
 * STRENGTH — non-blank, at least 32 characters, and not containing the {@code must-be} placeholder
 * wording. It could not test SECRECY. A secret that was committed to a public repository is long,
 * non-blank and not worded like a placeholder, so it sails through while being worth nothing. Every
 * value this repository has ever shipped as a default is therefore pinned by SHA-256 digest below
 * and can never be re-supplied — not through YAML, not through {@code JWT_SECRET}.
 *
 * <p>Digests only. The plaintexts are burned; re-introducing any of them to source in any form —
 * including "just for the deny-list" — would republish them.
 */
@Component
@Profile({"prod", "dev", "benchmark"})
@Slf4j
public class JwtSecretValidator {

  private static final int MIN_SECRET_LENGTH = 32;

  /**
   * RR-129 — a 32+ byte random secret trivially exceeds this; only degenerate secrets fall below.
   */
  private static final int MIN_DISTINCT_CHARS = 6;

  private static final String DEV_PLACEHOLDER_MARKER = "must-be";

  /**
   * SHA-256 digests (lower-case hex) of every JWT secret this repository has published as a
   * committed default. Sourced from the git history of {@code application*.yml}; treat as burned
   * forever. Adding a value here is one-way — never remove one.
   */
  private static final Set<String> COMPROMISED_SECRET_DIGESTS =
      Set.of(
          // application.yml committed default (present since c51ce15ca)
          "d110fe96843f893231f1af1d6293683bd9771eab3c47106d7b941dce1ade5d1d",
          // application-dev.yml JWT_SECRET fallback
          "56bde53456dbae8a42d91bc548131be9c0f97de7edacd1d572da0e5b10bf29c8",
          // application-benchmark.yml JWT_SECRET fallback
          "5efd301f4374ec62fd04598f3a8287a9099948ff30a33b116c0b3911ca407a18",
          // application-prod.yml JWT_SECRET fallback
          "ff00b1fc64baa81e7b73c41587166ce4079a1da9d96fbac21e2c542aa82412b2",
          // later local-only placeholder that also reached the shared profiles' history
          "0ce3d1ee9fa6b1da3ef4a19215e22df13b922506929348e1c0af29a006595e4a");

  @Value("${app.jwt.secret:}")
  private String jwtSecret;

  /**
   * Operator-extensible deny-list of SHA-256 digests (lower-case hex), for secrets burned after
   * this build shipped. Ships empty; never accepts plaintext.
   */
  @Value("${app.jwt.denied-secret-sha256:}")
  private List<String> deniedSecretDigests = List.of();

  @PostConstruct
  void validate() {
    if (jwtSecret == null || jwtSecret.isBlank()) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret is not configured. Shared environments must supply JWT_SECRET"
              + " from the secret store (>= 32 chars); there is deliberately no built-in default"
              + " to fall back to.");
    }
    if (jwtSecret.toLowerCase(Locale.ROOT).contains(DEV_PLACEHOLDER_MARKER)) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret still contains the development placeholder. "
              + "Set a strong, unique secret (>= 32 chars) for this environment.");
    }
    // RR-129 — measure the actual HMAC key length in bytes, not Java char count. HS256 signs with
    // the secret's UTF-8 bytes and needs a 256-bit (32-byte) key. A base64/hex-encoded secret is
    // longer than its decoded key material, so byte length is a safe lower bound that never
    // under-rejects a genuine 256-bit key; a multi-byte UTF-8 secret is not over-credited either.
    int keyBytes = effectiveKeyBytes(jwtSecret);
    if (keyBytes < MIN_SECRET_LENGTH) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret is too weak ("
              + keyBytes
              + " bytes of key material). "
              + "Must be at least "
              + MIN_SECRET_LENGTH
              + " bytes (256 bits) for HS256.");
    }
    // RR-129 — reject a long-but-trivial secret (a repeated character, "aaaa…", "12121212…", a
    // single dictionary word). A cryptographically strong secret has broad character diversity; a
    // secret with almost no distinct characters is low entropy regardless of length.
    int distinct = distinctChars(jwtSecret);
    if (distinct < MIN_DISTINCT_CHARS) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret is too low-entropy (only "
              + distinct
              + " distinct characters). Generate a random secret, e.g. `openssl rand -base64 48`,"
              + " and supply it through the secret store.");
    }
    if (isKnownCompromised(jwtSecret)) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret matches a value that was published in this repository's history"
              + " (or an operator-configured burned secret). It can sign valid tokens for anyone"
              + " who reads that history. Rotate to a freshly generated secret in the secret store"
              + " and restart.");
    }
    log.info("[JwtSecretValidator] JWT secret validation passed (length={})", jwtSecret.length());
  }

  private boolean isKnownCompromised(String secret) {
    String digest = sha256Hex(secret);
    if (COMPROMISED_SECRET_DIGESTS.contains(digest)) return true;
    Set<String> configured = new HashSet<>();
    if (deniedSecretDigests != null) {
      for (String entry : deniedSecretDigests) {
        if (entry != null && !entry.isBlank()) {
          configured.add(entry.trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    return configured.contains(digest);
  }

  /**
   * RR-129 — the HMAC key length in bytes: the secret's UTF-8 byte length (the bytes HS256 actually
   * signs with). A base64/hex-encoded key decodes to fewer bytes, so this is a conservative lower
   * bound on real key strength and cannot false-reject a genuine 256-bit key.
   */
  static int effectiveKeyBytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  /** RR-129 — number of distinct characters, a cheap low-entropy proxy for degenerate secrets. */
  static int distinctChars(String value) {
    return (int) value.chars().distinct().count();
  }

  static String sha256Hex(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
