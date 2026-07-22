package com.navneet.executespec.apitests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.FileSystemTool;
import com.navneet.executespec.tool.GitTool;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates an OpenAPI 3.0 YAML specification for backend-only runs, post-merge.
 *
 * <p>Skips when:
 *
 * <ul>
 *   <li>Frontend files (.html, .jsx, .tsx, .vue, .svelte) exist in the workspace.
 *   <li>No Spring controller files (ending in *Controller.java / *Resource.java) are found.
 * </ul>
 *
 * <p>On success writes {@code api-docs/openapi.yaml} to the run workspace and marks the run with
 * {@code hasApiSpec=true} via StateStore.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiTestSuiteAgent {

  private final FileSystemTool fsTool;
  private final GitTool gitTool;
  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final SseBroadcaster broadcaster;
  private final StateStore stateStore;
  private final ApiTestSuiteProperties props;

  @Value("classpath:prompts/api-test-suite.txt")
  private Resource promptResource;

  @Value("${agent.workspace}")
  private String workspaceRoot;

  private static final String[] FRONTEND_EXTENSIONS = {".html", ".jsx", ".tsx", ".vue", ".svelte"};

  /**
   * RR-1282 — the discovery walk used to collect every regular file in the workspace before
   * filtering, select an unbounded number, and read each selected file in full into one prompt
   * string. On a large or deep repository that exhausts heap/I/O and then blows the provider
   * context window. These caps make discovery bounded and deterministic: the walk stops at a file
   * and wall-clock ceiling, at most {@link #MAX_API_FILES} files are selected, each file is
   * size-capped before it is opened, and the assembled prompt content is capped in aggregate.
   */
  private static final int MAX_CANDIDATES_SCANNED = 20_000;

  /** Largest single source file the agent will open for discovery or read into the prompt. */
  private static final long MAX_FILE_BYTES = 512_000;

  /** Wall-clock budget for the discovery walk on a pathologically large tree. */
  private static final long MAX_WALK_MILLIS = 30_000;

  /** Cap on the number of API route/controller files selected. */
  private static final int MAX_API_FILES = 60;

  /** Aggregate char budget for the assembled source payload sent to the model. */
  private static final int MAX_TOTAL_PROMPT_CHARS = 400_000;

  /** Called post-merge via virtual thread from RunOrchestrator. Non-fatal. */
  public void generateAsync(PipelineRun run) {
    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
    Thread.ofVirtual()
        .name("api-spec-" + run.getRunId())
        .start(snapshot.wrap(() -> safeGenerate(run)));
  }

  private void safeGenerate(PipelineRun run) {
    try {
      generate(run);
    } catch (Exception e) {
      log.error("ApiTestSuiteAgent failed for run {}: {}", run.getRunId(), e.getMessage(), e);
    }
  }

  private void generate(PipelineRun run) throws IOException {
    if (!props.isEnabled()) return;
    String runId = run.getRunId();

    Path ws = Path.of(workspaceRoot, runId);

    // Find API route/controller source files (any language)
    // Note: we no longer skip for frontend-only projects — if API controllers exist, generate the
    // spec.
    List<String> apiFiles = findApiRouteFiles(ws);
    if (apiFiles.isEmpty()) {
      log.info(
          "ApiTestSuiteAgent: no API route/controller files — skipping spec gen for run {}", runId);
      return;
    }

    log.info(
        "ApiTestSuiteAgent: generating OpenAPI spec for run {} ({} API file(s))",
        runId,
        apiFiles.size());
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            "api-test-suite",
            "Generating OpenAPI 3.0 spec from " + apiFiles.size() + " API file(s)..."));

    // Read API source
    String controllerContent = readFiles(runId, apiFiles);

    // Call LLM
    String system = promptResource.getContentAsString(StandardCharsets.UTF_8);
    ResolvedModel resolved = modelResolver.resolve("ApiTestSuiteAgent", run.getAiProviderEnum());
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(system),
                new UserMessage(PromptTrustBoundary.wrap("API_SOURCE_FILES", controllerContent))));

    var llmResponse =
        llmRouter.callText(
            resolved.providerName(),
            resolved.getModel(),
            prompt.getInstructions(),
            runId,
            "ApiTestSuiteAgent",
            "api-spec.generate");

    // Track tokens for billing accuracy
    try {
      int inTok = llmResponse.getMetadata().getUsage().getPromptTokens().intValue();
      int outTok = llmResponse.getMetadata().getUsage().getCompletionTokens().intValue();
      stateStore.addTokensToRun(runId, inTok, outTok);
      log.debug("ApiTestSuiteAgent tokens: run={} in={} out={}", runId, inTok, outTok);
    } catch (Exception e) {
      log.warn("ApiTestSuiteAgent: failed to track tokens for run {}: {}", runId, e.getMessage());
    }

    String yaml = llmResponse.getResult().getOutput().getText();

    yaml = stripMarkdownFences(yaml);

    // RR-1281 — a prefix check ("starts with openapi") let malformed, hallucinated or incomplete
    // text become the customer-visible authoritative API contract and poison downstream
    // detection/docs. Require a bounded safe YAML parse AND a real OpenAPI shape (version + at
    // least
    // one path with at least one HTTP operation) before this content is ever committed/flagged.
    OpenApiValidation validation = validateOpenApi(yaml);
    if (!validation.valid()) {
      log.warn(
          "ApiTestSuiteAgent: generated OpenAPI failed validation for run {} ({}) — not saved",
          runId,
          validation.reason());
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId,
              "api-test-suite",
              "OpenAPI spec rejected — " + validation.reason() + "; not committed."));
      return;
    }

    // Persist spec to filesystem
    fsTool.writeFile(runId, "api-docs/openapi.yaml", yaml);

    // Commit the spec to git on the current branch (main, post-merge).
    // This prevents a retried worker's `git add .` from accidentally staging
    // the untracked file onto a task branch, which would cause merge conflicts
    // or silently overwrite the spec during subsequent retry merges.
    //
    // RR-1283 — stage ONLY the generated spec. The run workspace is shared with concurrently
    // running post-merge agents, so a whole-workspace `git add -A` here could sweep another
    // agent's half-written or rejected output into a commit labelled "update openapi.yaml".
    var commit =
        gitTool.commitPaths(
            runId, "chore: update api-docs/openapi.yaml [auto]", List.of("api-docs/openapi.yaml"));
    if (!commit.persisted()) {
      log.warn(
          "ApiTestSuiteAgent: OpenAPI spec not committed for run {} — {}", runId, commit.detail());
      return;
    }

    stateStore.markApiSpecReady(runId);
    broadcaster.emit(runId, RunEvent.apiSpecReady(runId));
    log.info("ApiTestSuiteAgent: OpenAPI spec written and committed for run {}", runId);
  }

  static String stripMarkdownFences(String yaml) {
    if (yaml == null) return "";
    String stripped = yaml.trim();
    if (stripped.startsWith("```")) {
      int firstNewline = stripped.indexOf('\n');
      int lastFence = stripped.lastIndexOf("```");
      if (firstNewline >= 0 && lastFence > firstNewline) {
        stripped = stripped.substring(firstNewline + 1, lastFence).trim();
      }
    }
    return stripped;
  }

  /** RR-1281 — the outcome of validating generated OpenAPI content. */
  record OpenApiValidation(boolean valid, String reason) {
    static OpenApiValidation ok() {
      return new OpenApiValidation(true, "ok");
    }

    static OpenApiValidation invalid(String reason) {
      return new OpenApiValidation(false, reason);
    }
  }

  /** Recognised HTTP operation keys for the OpenAPI {@code paths} objects. */
  private static final Set<String> HTTP_OPERATIONS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  /**
   * RR-1281 — bounded safe YAML parse plus a deterministic OpenAPI-shape check. Uses SnakeYAML's
   * {@code SafeConstructor} (no arbitrary Java object instantiation) with explicit code-point and
   * alias limits so a hostile document cannot cause resource exhaustion, then requires the parsed
   * document to be a mapping carrying an {@code openapi} version string and at least one path whose
   * value declares at least one HTTP operation. Returns the first failure reason rather than
   * throwing, so the caller can log it and skip the commit.
   */
  static OpenApiValidation validateOpenApi(String yaml) {
    if (yaml == null || yaml.isBlank()) {
      return OpenApiValidation.invalid("empty output");
    }
    if (yaml.length() > 5_000_000) {
      return OpenApiValidation.invalid("document exceeds size bound");
    }
    Object parsed;
    try {
      org.yaml.snakeyaml.LoaderOptions options = new org.yaml.snakeyaml.LoaderOptions();
      options.setCodePointLimit(5_000_000);
      options.setMaxAliasesForCollections(50);
      options.setAllowRecursiveKeys(false);
      org.yaml.snakeyaml.Yaml safeYaml =
          new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options));
      parsed = safeYaml.load(yaml);
    } catch (RuntimeException parseFailure) {
      return OpenApiValidation.invalid("not parseable YAML");
    }
    if (!(parsed instanceof Map<?, ?> root)) {
      return OpenApiValidation.invalid("root is not a mapping");
    }
    Object version = root.get("openapi");
    if (!(version instanceof String versionStr) || !versionStr.trim().startsWith("3")) {
      return OpenApiValidation.invalid("missing or unsupported openapi version");
    }
    if (!(root.get("info") instanceof Map)) {
      return OpenApiValidation.invalid("missing info object");
    }
    if (!(root.get("paths") instanceof Map<?, ?> paths) || paths.isEmpty()) {
      return OpenApiValidation.invalid("no paths defined");
    }
    for (Object pathItem : paths.values()) {
      if (pathItem instanceof Map<?, ?> item) {
        for (Object key : item.keySet()) {
          if (key instanceof String opKey
              && HTTP_OPERATIONS.contains(opKey.toLowerCase(java.util.Locale.ROOT))) {
            return OpenApiValidation.ok();
          }
        }
      }
    }
    return OpenApiValidation.invalid("no HTTP operations under any path");
  }

  boolean hasFrontendFiles(Path ws) {
    try (Stream<Path> s = Files.walk(ws)) {
      return s.filter(Files::isRegularFile)
          .filter(p -> !p.toString().contains("node_modules"))
          .filter(p -> !p.toString().contains(".git"))
          .anyMatch(
              p -> {
                String name = p.getFileName().toString();
                for (String ext : FRONTEND_EXTENSIONS) {
                  if (name.endsWith(ext)) return true;
                }
                return false;
              });
    } catch (IOException e) {
      return false;
    }
  }

  /** Detect API route/controller files across all supported languages. */
  private List<String> findApiRouteFiles(Path ws) {
    // File-name patterns that strongly indicate REST API route definitions
    Set<String> javaPatterns = Set.of("Controller.java", "Resource.java");
    Set<String> exactNames =
        Set.of(
            "urls.py", // Django
            "views.py", // Django REST
            "main.go" // Go (often single-file API servers)
            );

    // RR-1282 — bound the walk: stop at a candidate ceiling and a wall-clock deadline, skip
    // symbolic links (a repository clone is customer-controlled), and cap each file's size before
    // it is ever opened. The candidate list can no longer grow with the repository without limit.
    long deadline = System.currentTimeMillis() + MAX_WALK_MILLIS;
    try (Stream<Path> s = Files.walk(ws)) {
      List<Path> candidates = new java.util.ArrayList<>();
      var iter = s.iterator();
      while (iter.hasNext()) {
        if (candidates.size() >= MAX_CANDIDATES_SCANNED || System.currentTimeMillis() > deadline) {
          log.warn(
              "ApiTestSuiteAgent: discovery walk hit a bound after {} candidates",
              candidates.size());
          break;
        }
        Path p = iter.next();
        String path = p.toString();
        if (path.contains("node_modules")
            || path.contains(".git")
            || path.contains("__pycache__")
            || path.contains("vendor")) continue;
        if (Files.isSymbolicLink(p)) continue;
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
        candidates.add(p);
      }

      // First pass: collect files matching name patterns
      List<String> results = new java.util.ArrayList<>();
      for (Path p : candidates) {
        if (results.size() >= MAX_API_FILES) break;
        String name = p.getFileName().toString();
        String rel = ws.relativize(p).toString();

        // Java Spring controllers
        if (javaPatterns.stream().anyMatch(name::endsWith)) {
          results.add(rel);
          continue;
        }
        // Exact name matches
        if (exactNames.contains(name)) {
          results.add(rel);
          continue;
        }
        // Python: files with route decorators (FastAPI/Flask)
        if (name.endsWith(".py")
            && (name.contains("route")
                || name.contains("api")
                || name.equals("app.py")
                || name.equals("server.py"))) {
          results.add(rel);
          continue;
        }
        // Node/TS: route files
        if ((name.endsWith(".js") || name.endsWith(".ts"))
            && (name.contains("route")
                || name.contains("controller")
                || name.equals("server.js")
                || name.equals("server.ts")
                || name.equals("app.js")
                || name.equals("app.ts")
                || name.equals("index.js")
                || name.equals("index.ts"))) {
          results.add(rel);
          continue;
        }
        // Go: handler files
        if (name.endsWith(".go")
            && (name.contains("handler")
                || name.contains("route")
                || name.contains("controller")
                || name.contains("api"))) {
          results.add(rel);
          continue;
        }
        // Rust: route/handler files
        if (name.endsWith(".rs")
            && (name.contains("route")
                || name.contains("handler")
                || name.equals("main.rs")
                || name.equals("lib.rs"))) {
          results.add(rel);
          continue;
        }
      }

      // If no files found by name, do content scan for route patterns in Python/Node entry files
      if (results.isEmpty()) {
        results = scanForRoutePatterns(ws, candidates);
      }

      return results;
    } catch (IOException e) {
      return List.of();
    }
  }

  /** Fallback: scan source files for common route decorator/function patterns. */
  private List<String> scanForRoutePatterns(Path ws, List<Path> candidates) {
    List<String> results = new java.util.ArrayList<>();
    Set<String> routePatterns =
        Set.of(
            "@app.route",
            "@app.get",
            "@app.post",
            "@router.", // Python Flask/FastAPI
            "app.get(",
            "app.post(",
            "app.put(",
            "app.delete(", // Node Express
            "router.get(",
            "router.post(", // Node Express Router
            "HandleFunc(",
            "gin.Default(",
            "echo.New(", // Go
            "#[get(",
            "#[post(",
            "web::resource" // Rust Actix
            );

    for (Path p : candidates) {
      String name = p.getFileName().toString();
      if (!(name.endsWith(".py")
          || name.endsWith(".js")
          || name.endsWith(".ts")
          || name.endsWith(".go")
          || name.endsWith(".rs"))) continue;

      try {
        // RR-1282 — check the size BEFORE reading so a large file is skipped without allocating it.
        if (Files.size(p) > MAX_FILE_BYTES) continue;
        String content = Files.readString(p);
        if (content.length() > 50_000) continue; // skip large files
        for (String pattern : routePatterns) {
          if (content.contains(pattern)) {
            results.add(ws.relativize(p).toString());
            break;
          }
        }
      } catch (IOException ignored) {
      }

      if (results.size() >= 10) break; // cap to avoid reading too many files
    }
    return results;
  }

  /**
   * RR-1282 — assemble the source payload under an aggregate char budget so a set of large files
   * cannot blow the provider context window or run up unbounded input cost. Reads stop once the
   * budget is reached and record how many files were omitted so the omission is explicit rather
   * than silent.
   */
  private String readFiles(String runId, List<String> relativePaths) {
    var sb = new StringBuilder();
    int included = 0;
    for (String f : relativePaths) {
      if (sb.length() >= MAX_TOTAL_PROMPT_CHARS) {
        int omitted = relativePaths.size() - included;
        sb.append("=== (")
            .append(omitted)
            .append(" further file(s) omitted — aggregate source budget reached) ===\n");
        break;
      }
      String result = fsTool.readFile(runId, f);
      if (!result.startsWith("ERROR")) {
        sb.append("=== ").append(f).append(" ===\n").append(result).append("\n\n");
        included++;
      }
    }
    return sb.toString();
  }
}
