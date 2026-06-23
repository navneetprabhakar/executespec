package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

final class PreviewModeDetector {

  private static final long MAX_FILE_SIZE = 50 * 1024;

  private PreviewModeDetector() {}

  static PreviewMode detect(PreviewProjectType type, Path workspace) {
    if (type == PreviewProjectType.REACT_NATIVE_EXPO) return PreviewMode.MOBILE_EXPO_GO;
    if (isMcpProject(type, workspace)) return PreviewMode.MCP_INSPECTOR;
    if (isCliProject(type, workspace)) return PreviewMode.CLI_TERMINAL;

    if (type == PreviewProjectType.FULL_STACK
        || type == PreviewProjectType.NODE_FRONTEND
        || type == PreviewProjectType.STATIC_HTML) {
      return PreviewMode.FULL_STACK;
    }
    return PreviewMode.REST;
  }

  private static boolean isMcpProject(PreviewProjectType type, Path workspace) {
    if (type == PreviewProjectType.NODE_BACKEND || type == PreviewProjectType.NODE_FRONTEND) {
      JsonNode pkg = PreviewNodeSignals.parsePackageJson(workspace);
      if (pkg != null) {
        for (String field : List.of("dependencies", "devDependencies")) {
          JsonNode deps = pkg.get(field);
          if (deps != null && (deps.has("@modelcontextprotocol/sdk") || deps.has("mcp"))) {
            return true;
          }
        }
      }
    }
    if (type == PreviewProjectType.PYTHON) {
      return PreviewPythonSignals.hasDependency(workspace, "mcp")
          || PreviewPythonSignals.hasDependency(workspace, "modelcontextprotocol");
    }
    return false;
  }

  private static boolean isCliProject(PreviewProjectType type, Path workspace) {
    return switch (type) {
      case PYTHON -> isPythonCli(workspace);
      case GO -> !hasGoWebImports(workspace);
      case RUST -> isRustCli(workspace);
      case NODE_BACKEND -> isNodeCli(workspace);
      case JAVA -> true;
      case NODE_FRONTEND, SPRING_BOOT, FULL_STACK, STATIC_HTML, REACT_NATIVE_EXPO -> false;
    };
  }

  private static boolean isPythonCli(Path workspace) {
    if (Files.exists(workspace.resolve("manage.py"))) return false;
    if (PreviewPythonSignals.hasDependency(workspace, "fastapi")
        || PreviewPythonSignals.hasDependency(workspace, "flask")
        || PreviewPythonSignals.hasDependency(workspace, "uvicorn")
        || PreviewPythonSignals.hasDependency(workspace, "django")) {
      return false;
    }
    return PreviewPythonSignals.findPythonFileWithPattern(
            workspace,
            "from fastapi",
            "import fastapi",
            "from flask",
            "import flask",
            "from django")
        == null;
  }

  private static boolean isRustCli(Path workspace) {
    Path cargo = workspace.resolve("Cargo.toml");
    return !Files.exists(cargo)
        || !fileContainsPattern(cargo, "actix-web", "rocket", "axum", "warp", "hyper");
  }

  private static boolean isNodeCli(Path workspace) {
    JsonNode pkg = PreviewNodeSignals.parsePackageJson(workspace);
    if (pkg != null) {
      for (String field : List.of("dependencies", "devDependencies")) {
        JsonNode deps = pkg.get(field);
        if (PreviewDependencySignals.hasBackendDependency(deps)) return false;
      }
    }
    return true;
  }

  private static boolean hasGoWebImports(Path workspace) {
    if (hasGoWebImportsInDir(workspace, false)) {
      return true;
    }
    for (String sub : List.of("cmd", "internal", "pkg")) {
      Path subDir = workspace.resolve(sub);
      if (Files.isDirectory(subDir) && hasGoWebImportsInDir(subDir, true)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasGoWebImportsInDir(Path dir, boolean recursive) {
    try (Stream<Path> files = recursive ? Files.walk(dir, 2) : Files.list(dir)) {
      return files
          .filter(p -> p.toString().endsWith(".go"))
          .limit(20)
          .anyMatch(
              f ->
                  fileContainsPattern(
                      f,
                      "net/http",
                      "gin-gonic",
                      "gofiber/fiber",
                      "labstack/echo",
                      "go-chi/chi",
                      "gorilla/mux"));
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean fileContainsPattern(Path file, String... patterns) {
    try {
      if (Files.size(file) > MAX_FILE_SIZE) return false;
      String content = Files.readString(file);
      for (String pattern : patterns) {
        if (content.contains(pattern)) return true;
      }
    } catch (IOException e) {
      return false;
    }
    return false;
  }
}
