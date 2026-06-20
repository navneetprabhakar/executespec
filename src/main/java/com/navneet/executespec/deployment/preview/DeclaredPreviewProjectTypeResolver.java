package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class DeclaredPreviewProjectTypeResolver {

  private DeclaredPreviewProjectTypeResolver() {}

  static Optional<PreviewProjectType> resolve(Path workspace, JsonNode projectJson) {
    String declared = projectJson.path("project_type").asText(null);
    if (declared == null || declared.isBlank() || "UNKNOWN".equalsIgnoreCase(declared)) {
      return Optional.empty();
    }

    PreviewProjectType fromPlan = PreviewProjectType.valueOf(normalize(declared));
    if (requiresPackageJson(fromPlan) && !Files.exists(workspace.resolve("package.json"))) {
      log.warn(
          "[Detect] Planner declared {} but no package.json found — falling back to detection",
          fromPlan);
      return Optional.empty();
    }
    if (fromPlan == PreviewProjectType.STATIC_HTML && !hasStaticHtml(workspace)) {
      log.warn(
          "[Detect] Planner declared STATIC_HTML but workspace has no .html files — refusing to"
              + " trust the declaration, falling back to file-based detection");
      return Optional.empty();
    }
    if (fromPlan == PreviewProjectType.SPRING_BOOT
        && Files.exists(workspace.resolve("backend/pom.xml"))
        && Files.exists(workspace.resolve("frontend/package.json"))) {
      log.info(
          "[Detect] Planner said {}, but workspace has backend/ + frontend/ — upgrading to"
              + " FULL_STACK",
          fromPlan);
      return Optional.of(PreviewProjectType.FULL_STACK);
    }
    if (fromPlan == PreviewProjectType.NODE_BACKEND
        && Files.exists(workspace.resolve("frontend/package.json"))) {
      log.info(
          "[Detect] Planner said {}, but frontend/package.json exists — upgrading to FULL_STACK",
          fromPlan);
      return Optional.of(PreviewProjectType.FULL_STACK);
    }

    log.info("[Detect] Using planner-declared project type: {}", fromPlan);
    return Optional.of(fromPlan);
  }

  private static String normalize(String declared) {
    String normalized = declared.trim().toUpperCase().replace('-', '_');
    return switch (normalized) {
      case "JAVA_SPRING_BOOT", "SPRING" -> "SPRING_BOOT";
      case "NODEJS_BACKEND", "NODE_JS_BACKEND" -> "NODE_BACKEND";
      case "NODEJS_FRONTEND", "NODE_JS_FRONTEND", "REACT", "VUE", "VITE" -> "NODE_FRONTEND";
      case "CLI" -> "JAVA";
      case "STATIC", "HTML" -> "STATIC_HTML";
      default -> normalized;
    };
  }

  private static boolean requiresPackageJson(PreviewProjectType type) {
    return type == PreviewProjectType.NODE_FRONTEND
        || type == PreviewProjectType.NODE_BACKEND
        || type == PreviewProjectType.FULL_STACK
        || type == PreviewProjectType.REACT_NATIVE_EXPO;
  }

  private static boolean hasStaticHtml(Path workspace) {
    try (Stream<Path> files = Files.list(workspace)) {
      return files.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".html"));
    } catch (IOException e) {
      return false;
    }
  }
}
