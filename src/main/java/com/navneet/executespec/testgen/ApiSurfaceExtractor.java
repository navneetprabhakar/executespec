package com.navneet.executespec.testgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Extracts actual API endpoint paths from a generated workspace. Used to inject real route paths
 * into the test planner prompt so tests target EXACT paths rather than guessing from conventions.
 */
@Component
@Slf4j
public class ApiSurfaceExtractor {

  // Spring: @GetMapping("/path"), @PostMapping("/path"), @RequestMapping(value="/path")
  private static final Pattern SPRING_MAPPING =
      Pattern.compile(
          "@(?:Get|Post|Put|Patch|Delete|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']+)[\"']");

  // Express.js: router.get('/path'), app.post('/path')
  private static final Pattern EXPRESS_ROUTE =
      Pattern.compile(
          "(?:router|app)\\.(?:get|post|put|patch|delete|all)\\s*\\(\\s*[\"'`]([^\"'`]+)[\"'`]");

  // FastAPI / Flask: @app.get("/path"), @router.post("/path")
  private static final Pattern PYTHON_ROUTE =
      Pattern.compile(
          "@(?:app|router|blueprint)\\.(?:get|post|put|patch|delete)\\s*\\(\\s*[\"']([^\"']+)[\"']");

  private static final int MAX_PATHS = 30;

  // RR-301 — do not mine routes from non-runtime trees. Test sources, vendored dependencies and
  // build output produce test-only, fictitious or dead endpoints that were then presented to the
  // planner as the real API surface. Only production source is scanned.
  private static final java.util.Set<String> EXCLUDED_DIR_SEGMENTS =
      java.util.Set.of(
          "node_modules",
          "vendor",
          "dist",
          "build",
          "target",
          ".git",
          ".gradle",
          ".venv",
          "venv",
          "__pycache__",
          ".next",
          "coverage",
          "out",
          "test",
          "tests",
          "__tests__",
          "spec",
          "specs");

  /**
   * Walk the workspace root and return raw API endpoint paths as a list. Used by ApiProber to probe
   * spec-required endpoints against a live preview.
   *
   * @param workspaceRoot root of the generated workspace
   * @return deduplicated list of paths (e.g. ["/api/tasks", "/api/users"]); empty if none found
   */
  public List<String> extractPaths(Path workspaceRoot) {
    if (workspaceRoot == null || !Files.exists(workspaceRoot)) {
      return List.of();
    }
    List<String> paths = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(workspaceRoot, 8)) {
      walk.filter(p -> isRuntimeSource(workspaceRoot, p))
          .forEach(
              file -> {
                try {
                  extractFromContent(Files.readString(file), paths);
                } catch (IOException e) {
                  // skip unreadable files
                }
              });
    } catch (IOException e) {
      log.debug("[ApiSurfaceExtractor] Walk failed for {}: {}", workspaceRoot, e.getMessage());
    }
    return paths.stream().distinct().limit(MAX_PATHS).toList();
  }

  /**
   * RR-301 — a candidate for route mining is a regular source file that lives outside every
   * non-runtime tree and is not itself a test/spec file.
   */
  private static boolean isRuntimeSource(Path root, Path path) {
    if (!Files.isRegularFile(path)) return false;
    String name = path.getFileName().toString();
    boolean sourceExt =
        name.endsWith(".java")
            || name.endsWith(".js")
            || name.endsWith(".ts")
            || name.endsWith(".py")
            || name.endsWith(".go")
            || name.endsWith(".kt");
    if (!sourceExt) return false;
    String lower = name.toLowerCase();
    if (lower.matches(".*(?:test|spec)\\.[a-z]+$")
        || lower.matches(".*test\\.(?:java|kt)$")
        || lower.startsWith("test_")) {
      return false;
    }
    Path relative = root.relativize(path);
    for (Path segment : relative) {
      if (EXCLUDED_DIR_SEGMENTS.contains(segment.toString().toLowerCase())) return false;
    }
    return true;
  }

  /**
   * Walk the workspace root and extract API endpoint paths. Returns a markdown-formatted summary of
   * found endpoints.
   *
   * @param workspaceRoot root of the generated workspace
   * @return markdown string listing endpoints; empty string if none found
   */
  public String extract(Path workspaceRoot) {
    if (workspaceRoot == null || !Files.exists(workspaceRoot)) {
      return "";
    }
    List<String> paths = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(workspaceRoot, 8)) {
      walk.filter(p -> isRuntimeSource(workspaceRoot, p))
          .forEach(
              file -> {
                try {
                  String content = Files.readString(file);
                  extractFromContent(content, paths);
                } catch (IOException e) {
                  // skip unreadable files
                }
              });
    } catch (IOException e) {
      log.debug("[ApiSurfaceExtractor] Walk failed for {}: {}", workspaceRoot, e.getMessage());
      return "";
    }

    if (paths.isEmpty()) return "";

    // Deduplicate and limit
    List<String> unique = paths.stream().distinct().limit(MAX_PATHS).toList();
    StringBuilder sb =
        new StringBuilder("API SURFACE (extracted from generated code — use EXACT paths):\n");
    for (String p : unique) {
      sb.append("- ").append(p).append("\n");
    }
    return sb.toString();
  }

  private void extractFromContent(String content, List<String> paths) {
    addMatches(SPRING_MAPPING.matcher(content), paths);
    addMatches(EXPRESS_ROUTE.matcher(content), paths);
    addMatches(PYTHON_ROUTE.matcher(content), paths);
  }

  private void addMatches(Matcher m, List<String> paths) {
    while (m.find() && paths.size() < MAX_PATHS) {
      String path = m.group(1);
      // RR-1588 — these literals come from LLM-generated source and are later turned into outbound
      // probe URLs. Only rooted relative paths leave this extractor; anything that could carry an
      // authority (`//host`, `@host`, `scheme:`) is dropped here rather than sanitised downstream.
      if (ApiProber.isProbablePath(path)) {
        paths.add(path);
      } else if (path != null && !path.isBlank()) {
        log.debug("[ApiSurfaceExtractor] Dropping non-path route literal");
      }
    }
  }
}
