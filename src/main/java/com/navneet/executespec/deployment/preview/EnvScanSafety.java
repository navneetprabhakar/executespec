package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.navneet.executespec.security.GeneratedSecretScanner;

/**
 * Safety helpers for {@link EnvVarDetector}'s scan of workspace-controlled files.
 *
 * <p>Extracted from {@code EnvVarDetector} to keep that class under the 600-line ceiling while
 * still closing two P1 findings:
 *
 * <ul>
 *   <li><b>RR-689</b> — real credential values copied into {@code .env.example} were emitted as
 *       display defaults straight into API responses, logs and UI. Never emit a default for a
 *       secret-classified variable, and additionally drop any value the generated-secret scanner
 *       recognises as a high-confidence credential even when the variable name looks innocuous.
 *   <li><b>RR-691</b> — the prior read pulled the WHOLE file into a String before truncating, so
 *       one sparse/large file allocated memory proportional to its full size. Read at most a byte
 *       ceiling from the stream instead.
 * </ul>
 */
final class EnvScanSafety {

  /** RR-691 — hard byte ceiling per candidate file; well above the 100k-character scan cap. */
  private static final int MAX_SCAN_READ_BYTES = 512 * 1024;

  private final GeneratedSecretScanner secretScanner = new GeneratedSecretScanner();

  /**
   * RR-689 — returns {@code null} (presence only) for a secret-classified variable, or for any
   * value recognised as a high-confidence credential; otherwise returns the harmless default
   * unchanged.
   */
  String sanitizeDefault(boolean secretClassified, String defaultValue) {
    if (defaultValue == null) return null;
    if (secretClassified) return null;
    return secretScanner.scan(defaultValue).isPresent() ? null : defaultValue;
  }

  /**
   * RR-691 — read at most {@link #MAX_SCAN_READ_BYTES} bytes from {@code file}, decoded as UTF-8.
   */
  String readBoundedUtf8(Path file) throws IOException {
    byte[] bounded;
    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      bounded = in.readNBytes(MAX_SCAN_READ_BYTES);
    }
    return new String(bounded, StandardCharsets.UTF_8);
  }
}
