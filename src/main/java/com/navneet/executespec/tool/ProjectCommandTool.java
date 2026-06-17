package com.navneet.executespec.tool;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Project-level command tool that auto-detects common install/test/lint/format commands and runs
 * them through {@link ShellTool}. Explicit {@code command} still wins.
 */
final class ProjectCommandTool implements Tool {

  enum Kind {
    INSTALL,
    TEST,
    LINT,
    FORMAT
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private final String name;
  private final String description;
  private final Kind kind;
  private final Tool shellTool;

  ProjectCommandTool(String name, String description, Kind kind, Tool shellTool) {
    this.name = name;
    this.description = description;
    this.kind = kind;
    this.shellTool = shellTool;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public String inputSchema() {
    return ProjectCommandSchemas.OPTIONAL_COMMAND_SCHEMA;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String command = textArg(args, "command");
    if (command == null) command = textArg(args, "plannerCommand");
    if (command == null) command = detect(ctx.workspaceRoot(), kind);
    if (command == null) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS,
          "no " + name + " command provided and none could be detected");
    }

    ObjectNode forwarded = JSON.createObjectNode();
    forwarded.put("command", command);
    if (args != null && args.hasNonNull("timeoutSeconds")) {
      forwarded.put("timeoutSeconds", args.get("timeoutSeconds").asInt());
    }
    return shellTool.execute(forwarded, ctx);
  }

  private static String textArg(JsonNode args, String field) {
    if (args == null || !args.hasNonNull(field)) return null;
    String text = args.get(field).asText(null);
    return text == null || text.isBlank() ? null : text;
  }

  private static String detect(Path root, Kind kind) {
    return switch (kind) {
      case INSTALL -> detectInstall(root);
      case TEST -> detectTest(root);
      case LINT -> detectLint(root);
      case FORMAT -> detectFormat(root);
    };
  }

  private static String detectInstall(Path root) {
    if (exists(root, "pnpm-lock.yaml")) return "pnpm install --frozen-lockfile";
    if (exists(root, "yarn.lock")) return "yarn install --frozen-lockfile";
    if (exists(root, "package-lock.json") || exists(root, "npm-shrinkwrap.json")) return "npm ci";
    if (exists(root, "package.json")) return "npm install";
    if (exists(root, "pom.xml")) return "mvn -q -DskipTests dependency:go-offline";
    if (exists(root, "build.gradle") || exists(root, "build.gradle.kts")) {
      return "./gradlew dependencies --quiet";
    }
    if (exists(root, "Cargo.toml")) return "cargo fetch";
    if (exists(root, "go.mod")) return "go mod download";
    if (exists(root, "requirements.txt")) return "python -m pip install -r requirements.txt";
    if (exists(root, "pyproject.toml") || exists(root, "setup.py"))
      return "python -m pip install -e .";
    return null;
  }

  private static String detectTest(Path root) {
    String node = detectNodeScript(root, "test");
    if (node != null) return node;
    if (exists(root, "pom.xml")) return "mvn -q test";
    if (exists(root, "build.gradle") || exists(root, "build.gradle.kts")) return "./gradlew test";
    if (exists(root, "Cargo.toml")) return "cargo test";
    if (exists(root, "go.mod")) return "go test ./...";
    if (exists(root, "pyproject.toml") || exists(root, "setup.py")) return "pytest -q";
    return null;
  }

  private static String detectLint(Path root) {
    String node = detectNodeScript(root, "lint");
    if (node != null) return node;
    if (exists(root, "Cargo.toml")) return "cargo clippy --all-targets --all-features";
    if (exists(root, "go.mod")) return "go vet ./...";
    if (exists(root, "ruff.toml") || exists(root, ".ruff.toml") || exists(root, "pyproject.toml")) {
      return "ruff check .";
    }
    return null;
  }

  private static String detectFormat(Path root) {
    String node = detectNodeScript(root, "format");
    if (node != null) return node;
    if (exists(root, "Cargo.toml")) return "cargo fmt";
    if (exists(root, "go.mod")) return "gofmt -w .";
    if (exists(root, "ruff.toml") || exists(root, ".ruff.toml") || exists(root, "pyproject.toml")) {
      return "ruff format .";
    }
    return null;
  }

  private static String detectNodeScript(Path root, String script) {
    if (!exists(root, "package.json")) return null;
    try {
      JsonNode scripts = JSON.readTree(root.resolve("package.json").toFile()).path("scripts");
      if (!scripts.hasNonNull(script) || !scripts.get(script).isTextual()) return null;
      return detectPackageManager(root) + " run " + script;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String detectPackageManager(Path root) {
    if (exists(root, "pnpm-lock.yaml")) return "pnpm";
    if (exists(root, "yarn.lock")) return "yarn";
    return "npm";
  }

  private static boolean exists(Path root, String rel) {
    return Files.exists(root.resolve(rel));
  }
}
