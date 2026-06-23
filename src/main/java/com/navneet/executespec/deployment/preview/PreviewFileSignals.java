package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class PreviewFileSignals {

  private static final long MAX_FILE_SIZE = 50 * 1024; // 50 KB

  private PreviewFileSignals() {}

  static boolean fileContainsPattern(Path file, String... patterns) {
    try {
      if (Files.size(file) > MAX_FILE_SIZE) return false;
      String content = Files.readString(file);
      for (String pattern : patterns) {
        assert pattern.indexOf('|') < 0
            : "fileContainsPattern pattern '"
                + pattern
                + "' contains '|' — use varargs OR semantics instead";
        if (content.contains(pattern)) return true;
      }
    } catch (IOException e) {
      log.debug("Failed to read file {}: {}", file, e.getMessage());
    }
    return false;
  }

  static boolean hasStaticHtml(Path workspace) {
    try (Stream<Path> files = Files.list(workspace)) {
      return files.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".html"));
    } catch (IOException e) {
      return false;
    }
  }

  static boolean hasJsOrTsFiles(Path workspace) {
    try (Stream<Path> stream = Files.list(workspace)) {
      return stream.anyMatch(
          p -> {
            String s = p.toString();
            return s.endsWith(".js") || s.endsWith(".ts") || s.endsWith(".mjs");
          });
    } catch (IOException e) {
      return false;
    }
  }

  static boolean hasJavaFiles(Path workspace) {
    try (Stream<Path> stream = Files.list(workspace)) {
      return stream.anyMatch(p -> p.toString().endsWith(".java"));
    } catch (IOException e) {
      return false;
    }
  }
}
