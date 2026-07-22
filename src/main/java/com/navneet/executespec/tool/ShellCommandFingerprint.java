package com.navneet.executespec.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * RR-121 — a keyed, non-reversible fingerprint of a shell command for diagnostic logging.
 *
 * <p>{@link ShellSandboxPolicy} rejects dangerous commands and used to log the full raw command
 * verbatim ({@code log.warn("SHELL BLOCKED (...): {}", command)}). An attempted command can carry
 * access tokens, signed URLs, environment values or embedded file contents, so copying it into
 * central application logs leaks exactly the material the policy just refused to run. This emits a
 * short SHA-256 prefix plus the command length instead: enough to correlate repeated blocks and
 * confirm two log lines refer to the same command, without disclosing its bytes. The rule id is
 * already carried in the surrounding log message, so no diagnostic value is lost.
 */
final class ShellCommandFingerprint {

  private ShellCommandFingerprint() {}

  static String of(String command) {
    if (command == null || command.isBlank()) {
      return "sha256:(empty)";
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(command.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(24);
      for (int i = 0; i < 6 && i < digest.length; i++) {
        hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16))
            .append(Character.forDigit(digest[i] & 0xF, 16));
      }
      return "sha256:" + hex + " (" + command.length() + " chars)";
    } catch (Exception e) {
      // A fingerprint that cannot be computed must still not fall back to the raw command.
      return "sha256:(unavailable) (" + command.length() + " chars)";
    }
  }
}
