package com.navneet.executespec.tool;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.programbench.ProgramBenchContainerStore;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.smoketest.TestCommandResolver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Structured LANGUAGE-AWARE test tool for patch-mode. Resolves the workspace's test framework via
 * {@link TestCommandResolver} (pytest / go test / npm / cargo / mvn / gradle — the same resolver
 * the scoring path trusts) and runs the requested tests inside the bench container (or the host
 * workspace when no container is registered), returning a parsed pass/fail envelope.
 *
 * <p>History: until 2026-07-04 (harness-resolution task 6) this tool was pytest-only while its
 * schema said "use this instead of shell for running tests" — on Go/JS/TS repos (65% of SWE-bench
 * Pro) that description was a lie that taught models to distrust the structured tools. The resolver
 * was always available; the tool just didn't use it.
 *
 * <p>Why structured: with {@code shell} the agent had to parse runner output itself — count
 * "passed" / "failed" lines, extract assertion errors. Models sometimes mis-counted, sometimes
 * treated a single-test-failed run as "everything failed." A typed envelope removes that parsing
 * burden. The envelope shape is identical across frameworks ({@code summary} / {@code passed} /
 * {@code failed} / {@code errored} / {@code exit_code} / {@code raw_tail}); pytest additionally
 * gets per-test extraction, Go gets {@code --- FAIL:} extraction, other frameworks lean on {@code
 * raw_tail} (deliberately — over-parsing is where these tools go to die).
 *
 * <p>Routing: same dispatch tree as {@link ShellTool} — SWE-bench container store first (login
 * shell for conda activation), then ProgramBench, then host ProcessBuilder.
 *
 * <p>Doubles as the gate for {@code completeTask} verification (V182 layer-3 in {@code
 * WorkerAgent}): the gate reads {@code "failed":[]} + {@code "errored":[]} + {@code "exit_code":0}
 * from the ok-envelope — every framework path emits those fields so the gate works on non-Python
 * repos too.
 */
@Component
@Slf4j
public class RunTestsTool implements Tool {

  /** Tail truncation for the verbatim output that goes into {@code raw_tail}. */
  static final int RAW_TAIL_CHARS = 4_000;

  /** Default per-call timeout when the resolver doesn't supply one. */
  static final int DEFAULT_TIMEOUT_SEC = 240;

  /** Raised 600 → 900 (task 6): full go/npm suites on Pro-scale repos exceed 10 minutes. */
  static final int MAX_TIMEOUT_SEC = 900;

  /** Hard cap on test ids per call so the LLM doesn't cram a 200-test list into one call. */
  static final int MAX_TEST_IDS = 20;

  /**
   * Allowlisted prefixes for the raw {@code command} override (also the remote/local-hands contract
   * — the TB adapter sends {@code command}). Constrains the override to real test runners; anything
   * else belongs in {@code shell} where the sandbox policy applies.
   */
  static final List<String> COMMAND_ALLOWLIST_PREFIXES =
      List.of(
          "python -m pytest",
          "python3 -m pytest",
          "pytest",
          "go test",
          "npm test",
          "yarn test",
          "pnpm test",
          "cargo test",
          "mvn ",
          "./gradlew ",
          "gradle ");

  private final ProgramBenchContainerStore programBenchContainers;
  private final com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers;
  private final ObjectMapper json;
  private final TestCommandResolver testCommandResolver;

  /**
   * RR-547 — every non-pytest path composes a shell string from model-controlled input and hands it
   * to {@code /bin/sh -c}, on the application host whenever no bench container is registered. That
   * string is now validated by the same {@link ShellSandboxPolicy} the {@code shell} tool uses, so
   * {@code runTests} can no longer be the way around it. Container dispatch skips the host policy
   * for the same reason {@link ShellTool} does.
   */
  private final ShellSandboxPolicy sandboxPolicy;

  /** Result-envelope construction; see {@link RunTestsEnvelope}. */
  private final RunTestsEnvelope envelopes;

  /**
   * RR-282 / RR-1674 — a policy check decides what the string may say; it does not decide what the
   * program may do once {@code /bin/sh} has it. When {@link
   * SandboxCommandRoutingService#routingEnabled()} is on, the host branch is dispatched into the
   * run's sandbox instead; when the sandbox cannot run it, the tool reports the tests unavailable
   * and does NOT retry on the host. Both sandbox flags are seeded false, so the default path is
   * unchanged.
   */
  private final SandboxCommandRoutingService sandboxRouting;

  @Autowired
  public RunTestsTool(
      ProgramBenchContainerStore programBenchContainers,
      com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers,
      ObjectMapper json,
      TestCommandResolver testCommandResolver,
      ShellSandboxPolicy sandboxPolicy,
      SandboxCommandRoutingService sandboxRouting) {
    this.programBenchContainers = programBenchContainers;
    this.swebenchContainers = swebenchContainers;
    this.json = json;
    this.testCommandResolver = testCommandResolver;
    this.sandboxPolicy = sandboxPolicy;
    this.sandboxRouting = sandboxRouting;
    this.envelopes = new RunTestsEnvelope(json);
  }

  /**
   * Compatibility constructor for focused tool tests. Deliberately supplies a REAL policy at the
   * default LOW guardrail rather than a null bypass, so a test cannot pass a command production
   * would refuse.
   */
  RunTestsTool(
      ProgramBenchContainerStore programBenchContainers,
      com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers,
      ObjectMapper json,
      TestCommandResolver testCommandResolver) {
    this(
        programBenchContainers,
        swebenchContainers,
        json,
        testCommandResolver,
        new ShellSandboxPolicy(),
        null);
  }

  @Override
  public String name() {
    return "runTests";
  }

  @Override
  public String description() {
    return "Runs tests using the project's detected framework (pytest, go test, npm/jest, cargo,"
        + " mvn, gradle) and returns a parsed pass/fail summary with exit code. Pass specific"
        + " tests via `tests` (pytest ids, Go test names or ./package paths, JS test file"
        + " paths, cargo filters, or JUnit class names), or omit it to run the module/suite."
        + " Prefer this over `shell` for tests — the envelope reports counts and failures"
        + " cleanly. Required before completeTask in patch mode: observe the failing test pass"
        + " before claiming the task is done.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "tests": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Tests to run, in the project's framework syntax: pytest ids ('test/foo.py::test_a'), Go test names ('TestFoo') or package paths ('./pkg/...'), JS test file paths, cargo test filters, or JUnit class names. Omit or pass [] to run the whole suite/module. Max 20.",
      "maxItems": 20
    },
    "test_ids": {
      "type": "array",
      "items": {"type": "string"},
      "description": "DEPRECATED alias of `tests` (kept for compatibility).",
      "maxItems": 20
    },
    "command": {
      "type": "string",
      "description": "Advanced: run this exact test command instead (must start with a known runner: pytest / go test / npm test / cargo test / mvn / gradle). Use only when the framework filters above cannot express what you need."
    },
    "timeoutSeconds": {
      "type": "integer",
      "description": "Optional timeout (default: framework-dependent, max 900).",
      "minimum": 1,
      "maximum": 900
    }
  },
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    // ---- parse inputs (tests preferred; test_ids kept as alias; command as override) ----
    JsonNode idsNode = args.hasNonNull("tests") ? args.path("tests") : args.path("test_ids");
    List<String> testIds = new ArrayList<>();
    if (idsNode.isArray()) {
      if (idsNode.size() > MAX_TEST_IDS) {
        return ToolResult.err(
            ToolResult.ErrorKind.INVALID_ARGS,
            "tests exceeds max of " + MAX_TEST_IDS + " per call; split into multiple calls");
      }
      for (JsonNode el : idsNode) {
        String id = el.asText("").trim();
        if (id.isEmpty()) continue;
        // Defence: test ids should never contain shell metachars. Layered on top of the
        // ProcessBuilder argument-array dispatch below (audit 2026-05-12 #77).
        String idRejection = rejectionForTestId(id);
        if (idRejection != null) {
          return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, idRejection);
        }
        testIds.add(id);
      }
    }
    String commandOverride =
        args.hasNonNull("command") ? args.get("command").asText("").trim() : "";

    // ---- resolve framework from workspace markers (bind-mounted, so container runs resolve
    // host-side too). Pytest-shaped ids force PYTEST even without markers (legacy behavior). ----
    Optional<TestCommandResolver.TestCommand> resolved =
        testCommandResolver == null
            ? Optional.empty()
            : testCommandResolver.resolve(ctx.workspaceRoot());
    TestCommandResolver.Framework framework =
        resolved.map(TestCommandResolver.TestCommand::framework).orElse(null);
    boolean pytestShapedIds = testIds.stream().anyMatch(id -> id.contains(".py"));
    if (framework == null
        || (pytestShapedIds && framework != TestCommandResolver.Framework.PYTEST)) {
      framework = TestCommandResolver.Framework.PYTEST;
    }

    int timeout =
        args.hasNonNull("timeoutSeconds")
            ? Math.min(args.get("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SEC), MAX_TIMEOUT_SEC)
            : Math.min(
                resolved
                    .map(TestCommandResolver.TestCommand::timeoutSeconds)
                    .orElse(DEFAULT_TIMEOUT_SEC),
                MAX_TIMEOUT_SEC);

    // ---- container routing (mirrors ShellTool). useLoginShell: SWE-bench images need
    // bash -lc for conda activation; harmless for other stacks. ----
    String containerId = null;
    String workdir = null;
    boolean useLoginShell = false;
    if (swebenchContainers != null) {
      String swCid = swebenchContainers.get(ctx.runId());
      if (swCid != null) {
        containerId = swCid;
        workdir = swebenchContainers.getWorkdir(ctx.runId());
        useLoginShell = true;
      }
    }
    if (containerId == null && programBenchContainers != null) {
      String pbCid = programBenchContainers.get(ctx.runId());
      if (pbCid != null) {
        containerId = pbCid;
        workdir = "/workspace";
      }
    }

    if (!commandOverride.isEmpty() && !allowlistedCommand(commandOverride, containerId != null)) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS,
          "command must start with a known test runner ("
              + String.join(", ", COMMAND_ALLOWLIST_PREFIXES).trim()
              + ") and contain no shell chaining — use the shell tool for anything else");
    }

    List<String> cmd;
    String shellProgram = null;
    if (!commandOverride.isEmpty()) {
      shellProgram = commandOverride;
      cmd = wrapShellCommand(containerId, workdir, commandOverride);
    } else if (framework == TestCommandResolver.Framework.PYTEST && !testIds.isEmpty()) {
      // Byte-identical legacy pytest path (pinned by RunTestsToolTest). This one dispatches an
      // argument array with no shell program, so there is nothing for the shell policy to read.
      cmd = buildProcessCommand(containerId, workdir, useLoginShell, testIds);
    } else {
      shellProgram =
          frameworkCommand(
              framework,
              testIds,
              resolved.map(TestCommandResolver.TestCommand::command).orElse(null));
      cmd = wrapShellCommand(containerId, workdir, shellProgram);
    }

    // RR-547 — on the host, whatever we are about to hand to `/bin/sh -c` must satisfy the same
    // policy the `shell` tool enforces. Without this, structured filters and the `command`
    // override were an unguarded route to host execution with the app process's authority.
    if (containerId == null && shellProgram != null && sandboxPolicy != null) {
      ShellSandboxPolicy.ValidationResult validation =
          sandboxPolicy.validate(shellProgram, ctx.workspaceRoot().toString());
      if (!validation.permitted()) {
        log.warn(
            "runTests blocked by sandbox command={} reason={}", shellProgram, validation.reason());
        return ToolResult.err(
            ToolResult.ErrorKind.FORBIDDEN,
            "test command blocked by sandbox: " + validation.reason());
      }
    }

    // RR-282 / RR-1674 — sandbox dispatch, ahead of any host ProcessBuilder. Bench cleanrooms keep
    // their own container boundary and are left exactly as they were.
    if (containerId == null && sandboxRouting != null && sandboxRouting.routingEnabled()) {
      SandboxExecResult sandboxed =
          sandboxRouting.execute(
              ctx.runId(), ctx.workspaceRoot(), cmd, Duration.ofSeconds(timeout));
      return switch (sandboxed.status()) {
        case COMPLETED -> {
          String sandboxOutput = SandboxCommandRoutingService.combinedOutput(sandboxed);
          yield ToolResult.ok(
              framework == TestCommandResolver.Framework.PYTEST
                  ? envelopes.parseAndEnvelope(ctx, testIds, sandboxed.exitCode(), sandboxOutput)
                  : envelopes.genericEnvelope(
                      ctx,
                      framework,
                      String.join(" ", cmd),
                      testIds,
                      sandboxed.exitCode(),
                      sandboxOutput));
        }
        case TIMED_OUT ->
            ToolResult.err(
                ToolResult.ErrorKind.TIMEOUT,
                framework + " tests exceeded " + timeout + "s inside the sandbox.");
          // Never a host retry: an unexecuted command is the containment holding, not a licence to
          // run repository-controlled test code as the service account.
        case UNAVAILABLE, REFUSED ->
            ToolResult.err(
                ToolResult.ErrorKind.UNAVAILABLE,
                "tests unavailable — the sandbox could not run them ("
                    + sandboxed.detail()
                    + "). They were NOT executed on the application host.");
      };
    }

    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      if (containerId == null) {
        pb.directory(ctx.workspaceRoot().toFile());
      }
      Map<String, String> env = pb.environment();
      String path = env.get("PATH");
      String home = env.get("HOME");
      String dockerHost = env.get("DOCKER_HOST");
      env.clear();
      if (path != null) env.put("PATH", path);
      if (home != null) env.put("HOME", home);
      if (dockerHost != null) env.put("DOCKER_HOST", dockerHost);
      pb.redirectErrorStream(true);

      Process proc = pb.start();
      boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        return ToolResult.err(
            ToolResult.ErrorKind.TIMEOUT,
            framework
                + " tests exceeded "
                + timeout
                + "s timeout. Try fewer/narrower tests per call or raise timeoutSeconds (max "
                + MAX_TIMEOUT_SEC
                + ").");
      }
      String output = new String(proc.getInputStream().readAllBytes());
      int exit = proc.exitValue();
      log.info(
          "runTests run={} framework={} ids={} exit={} outBytes={}",
          ctx.runId(),
          framework,
          testIds.size(),
          exit,
          output.length());
      String envelope =
          framework == TestCommandResolver.Framework.PYTEST
              ? envelopes.parseAndEnvelope(ctx, testIds, exit, output)
              : envelopes.genericEnvelope(
                  ctx, framework, String.join(" ", cmd), testIds, exit, output);
      return ToolResult.ok(envelope);
    } catch (IOException ioe) {
      log.warn("runTests IO error: {}", ioe.getMessage());
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    }
  }

  // ------------------------------------------------------------------ command construction

  /**
   * Per-framework test command with filters (returned as ONE shell string; ids are
   * metachar-screened above and single-token, so plain joining is safe).
   */
  static String frameworkCommand(
      TestCommandResolver.Framework framework, List<String> ids, String suiteCommand) {
    switch (framework) {
      case GO -> {
        if (ids.isEmpty()) return suiteCommand != null ? suiteCommand : "go test ./...";
        List<String> packages = ids.stream().filter(id -> id.contains("/")).toList();
        List<String> names = ids.stream().filter(id -> !id.contains("/")).toList();
        StringBuilder sb = new StringBuilder("go test");
        if (!names.isEmpty()) {
          sb.append(" -run '").append(String.join("|", names)).append("'");
        }
        sb.append(" ").append(packages.isEmpty() ? "./..." : String.join(" ", packages));
        return sb.toString();
      }
      case JEST -> {
        if (ids.isEmpty()) return suiteCommand != null ? suiteCommand : "npm test --silent";
        return "npm test --silent -- " + String.join(" ", ids);
      }
      case CARGO -> {
        if (ids.isEmpty()) return suiteCommand != null ? suiteCommand : "cargo test";
        return "cargo test " + String.join(" ", ids);
      }
      case SUREFIRE -> {
        String base = suiteCommand != null ? suiteCommand : "mvn -q test";
        if (ids.isEmpty()) return base;
        if (base.startsWith("mvn")) return base + " -Dtest=" + String.join(",", ids);
        // gradle
        StringBuilder sb = new StringBuilder(base);
        for (String id : ids) sb.append(" --tests ").append(id);
        return sb.toString();
      }
      default -> {
        // PYTEST suite mode (empty ids) or UNKNOWN.
        if (ids.isEmpty()) return suiteCommand != null ? suiteCommand : "python3 -m pytest -q";
        return "python -m pytest " + String.join(" ", ids) + " --tb=short --no-header -q";
      }
    }
  }

  /** Wraps a shell command for container (bash -lc) or host (sh -c) execution. */
  static List<String> wrapShellCommand(String containerId, String workdir, String shellCmd) {
    List<String> cmd = new ArrayList<>();
    if (containerId != null) {
      cmd.add("docker");
      cmd.add("exec");
      cmd.add("-w");
      cmd.add(workdir);
      cmd.add(containerId);
      cmd.add("/bin/bash");
      cmd.add("-lc");
      cmd.add(shellCmd);
    } else {
      cmd.add("/bin/sh");
      cmd.add("-c");
      cmd.add(shellCmd);
    }
    return cmd;
  }

  /**
   * The raw {@code command} override must start with a known runner AND be free of shell chaining
   * chars. {@code |} (go -run regex alternation) is permitted only inside a container — there the
   * container is the sandbox (network none); on the host a pipe could chain to arbitrary binaries.
   */
  static boolean allowlistedCommand(String command, boolean inContainer) {
    String c = command.strip();
    if (COMMAND_ALLOWLIST_PREFIXES.stream().noneMatch(c::startsWith)) return false;
    String screened = inContainer ? c.replace("|", "") : c;
    return !containsShellMetachar(screened);
  }

  static List<String> buildProcessCommand(
      String containerId, String workdir, boolean useLoginShell, List<String> testIds) {
    List<String> cmd = new ArrayList<>();
    if (containerId != null) {
      cmd.add("docker");
      cmd.add("exec");
      cmd.add("-w");
      cmd.add(workdir);
      cmd.add(containerId);
      if (useLoginShell) {
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add("python -m pytest \"$@\" --tb=short --no-header -q");
        cmd.add("_");
        cmd.addAll(testIds);
        return cmd;
      }
    }
    // Host runtimes (including supported macOS local development and modern distro images) expose
    // Python 3 as `python3`; `python` is not guaranteed and caused otherwise-valid targeted pytest
    // calls to fail while the resolver-driven whole suite passed. Benchmark containers keep their
    // existing `python` contract because their conda/testbed images provide it.
    cmd.add(containerId == null ? "python3" : "python");
    cmd.add("-m");
    cmd.add("pytest");
    cmd.addAll(testIds);
    cmd.add("--tb=short");
    cmd.add("--no-header");
    cmd.add("-q");
    return cmd;
  }

  /**
   * RR-547 — per-id admission for the framework-filter paths. Beyond {@link
   * #containsShellMetachar}, this rejects quotes and whitespace, which were both MISSING: every
   * non-pytest path joins ids into ONE shell string, and the Go path wraps them in single quotes
   * (`-run '<names>'`), so an apostrophe closed our quoting and injected runner arguments while a
   * space started a new argument. A leading {@code -} is option injection into whichever runner
   * receives the joined string.
   *
   * @return a rejection reason, or {@code null} when the id is acceptable
   */
  static String rejectionForTestId(String id) {
    if (containsShellMetachar(id)) {
      return "test id contains forbidden shell metachars: " + id;
    }
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      if (c == '\'' || c == '"' || Character.isWhitespace(c)) {
        return "test id may not contain quotes or whitespace: " + id;
      }
    }
    if (id.startsWith("-")) {
      return "test id may not start with '-' (runner options are not accepted here): " + id;
    }
    return null;
  }

  /**
   * Returns true if {@code id} contains any shell metachar that could escape pytest argument
   * context if a later refactor stops using ProcessBuilder arg arrays. Defence-in-depth — audit
   * 2026-05-12 review item O #77.
   */
  static boolean containsShellMetachar(String id) {
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      switch (c) {
        case ';':
        case '&':
        case '|':
        case '$':
        case '`':
        case '<':
        case '>':
        case '(':
        case ')':
        case '\\':
        case '\n':
        case '\r':
        case '\0':
          return true;
        default:
          // ASCII control chars (0x01-0x1F minus already-handled tab) — refuse.
          if (c < 0x20 && c != '\t') return true;
      }
    }
    return false;
  }
}
