package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class PreviewNodeSignals {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern PORT_PATTERN =
      Pattern.compile("(?:PORT\\s*\\|\\|\\s*(\\d{4})|listen\\((\\d{4}))");

  private PreviewNodeSignals() {}

  static JsonNode parsePackageJson(Path workspace) {
    Path file = workspace.resolve("package.json");
    if (!Files.exists(file)) return null;
    try {
      return MAPPER.readTree(file.toFile());
    } catch (IOException e) {
      log.debug("Failed to parse package.json at {}: {}", file, e.getMessage());
      return null;
    }
  }

  static String findEntryPoint(Path workspace) {
    JsonNode pkg = parsePackageJson(workspace);
    if (pkg != null && pkg.has("main")) {
      String mainField = pkg.get("main").asText("");
      if (!mainField.isBlank() && Files.exists(workspace.resolve(mainField))) {
        return mainField;
      }
    }

    String[] candidates = {
      "index.js",
      "server.js",
      "app.js",
      "main.js",
      "index.mjs",
      "server.mjs",
      "app.mjs",
      "src/index.js",
      "src/server.js",
      "src/app.js",
      "src/index.mjs",
      "src/server.mjs"
    };
    for (String name : candidates) {
      if (Files.exists(workspace.resolve(name))) return name;
    }
    return firstRootJsFile(workspace);
  }

  static String findEntryPointInDir(Path dir) {
    String[] candidates = {
      "index.js",
      "server.js",
      "app.js",
      "main.js",
      "index.mjs",
      "server.mjs",
      "app.mjs",
      "src/index.js",
      "src/server.js",
      "src/app.js"
    };
    for (String name : candidates) {
      if (Files.exists(dir.resolve(name))) return name;
    }
    return firstRootJsFile(dir);
  }

  static int detectBackendPort(Path workspace, JsonNode pkg) {
    Path mainFile = null;
    if (pkg.has("main")) {
      mainFile = workspace.resolve(pkg.get("main").asText());
    }
    if (mainFile == null || !Files.exists(mainFile)) {
      mainFile = workspace.resolve("server.js");
    }
    if (!Files.exists(mainFile)) {
      mainFile = workspace.resolve("index.js");
    }
    if (Files.exists(mainFile)) {
      try {
        String content = Files.readString(mainFile);
        var matcher = PORT_PATTERN.matcher(content);
        if (matcher.find()) {
          String port = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
          return Integer.parseInt(port);
        }
      } catch (IOException e) {
        log.debug("Failed to read backend main file: {}", e.getMessage());
      }
    }
    return 3000;
  }

  private static String firstRootJsFile(Path workspace) {
    try (var stream = Files.list(workspace)) {
      return stream
          .filter(
              p -> {
                String fn = p.getFileName().toString();
                return fn.endsWith(".js") || fn.endsWith(".mjs");
              })
          .map(p -> p.getFileName().toString())
          .sorted()
          .findFirst()
          .orElse("index.js");
    } catch (Exception e) {
      return "index.js";
    }
  }
}
