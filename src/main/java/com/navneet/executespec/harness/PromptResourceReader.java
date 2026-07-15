package com.navneet.executespec.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;

/**
 * Reads optional prompt resources used by the executor's dynamic provider/project overlays.
 *
 * <p>Dynamic keys originate from planner/provider output, so resource lookup is deliberately
 * fail-closed: only a single safe classpath path segment is accepted. A missing or invalid key is
 * treated as an absent optional overlay, preserving the executor's base prompt.
 */
final class PromptResourceReader {

  private static final String SAFE_SEGMENT = "[a-z0-9][a-z0-9-]*";

  private PromptResourceReader() {}

  static String readOptional(String directory, String key) {
    if (directory == null || directory.isBlank() || !isSafeSegment(key)) return "";
    String path = directory.endsWith("/") ? directory + key + ".md" : directory + "/" + key + ".md";
    ClassPathResource resource = new ClassPathResource(path);
    if (!resource.exists()) return "";
    try {
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  static boolean isSafeSegment(String key) {
    return key != null && key.matches(SAFE_SEGMENT);
  }
}
