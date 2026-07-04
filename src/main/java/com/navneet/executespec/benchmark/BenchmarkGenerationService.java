package com.navneet.executespec.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.CodeGenEngine;
import com.navneet.executespec.engine.EngineOutcome;
import com.navneet.executespec.engine.EngineRequest;
import com.navneet.executespec.engine.EngineResult;
import com.navneet.executespec.engine.VerifyMode;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.swebench.SwebenchContainerStore;
import com.navneet.executespec.swebench.SwebenchInstanceLoader;
import com.navneet.executespec.swebench.SwebenchProperties;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lean, synchronous benchmark code-generation API.
 *
 * <p><b>Why this exists.</b> Benchmarks need the same codegen worker seam as product PATCH-mode,
 * but not the post-codegen product lifecycle (credits, preview, validation report, email listeners,
 * auto-push). This wrapper owns benchmark-only setup/capture while keeping generation in {@link
 * CodeGenEngine}.
 *
 * <p>This service does <b>only code generation</b>: it calls {@code CodeGenEngineImpl}, which wires
 * the same {@code WorkerAgent} → tools → {@code LlmRouter} loop inside the cleanroom container
 * <i>directly and synchronously</i>, and returns the patch. No {@code submitRun}, no async, no
 * listeners, no credits, no preview/report/email. The HTTP call blocks (~2–4 min for a Live
 * instance) and hands back the diff — no polling, no harvesting.
 *
 * <p>It still creates ONE minimal {@link PipelineRun} row, because the worker reads two things from
 * it: {@code hardCapUsd} (the dollar budget cap, in {@code WorkerAgent}) and {@code sourceType}
 * (the PATCH test-verification gate, in {@code CodeGenEngineImpl} — {@code "benchmark"} ⇒ gate OFF
 * for blind runs). That row is the minimal subset of what {@code submitRun} sets, not the full
 * lifecycle.
 *
 * <p>Blind: the agent sees only the issue {@code problem_statement}; the hidden tests are applied
 * externally at scoring.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkGenerationService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final double DEFAULT_HARD_CAP_USD = 8.0;

  private final SwebenchProperties props;
  private final SwebenchInstanceLoader loader;
  private final SwebenchContainerStore containerStore;
  private final CodeGenEngine codeGenEngine;
  private final PipelineRunRepository runRepo;
  private final RunCostTracker costTracker;

  private final Object generationSlotsLock = new Object();
  private Semaphore generationSlots;
  private int generationSlotLimit;

  /** Synchronous result returned straight to the HTTP caller. */
  public record GenerateResult(
      String runId,
      String instanceId,
      String provider,
      String model,
      String outcome,
      int iterations,
      int toolCalls,
      long patchBytes,
      double costUsd,
      String submissionPatch,
      String predsJson,
      String proPatchJson) {}

  /**
   * Generate a patch for one SWE-bench instance, blind, synchronously. Blocks until the worker
   * terminates, then returns the captured diff + cost.
   */
  public GenerateResult generate(String instanceId, String aiProvider, String modelName) {
    if (!props.isEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "swebench is disabled (set swebench.enabled=true)");
    }
    if (instanceId == null || instanceId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instanceId required");
    }

    SwebenchInstanceLoader.SwebenchInstance instance = loader.findById(instanceId);
    String image = loader.imageNameFor(instanceId);
    String provider =
        aiProvider != null && !aiProvider.isBlank() ? aiProvider : props.getDefaultProvider();
    String model =
        modelName != null && !modelName.isBlank() ? modelName : defaultPlanningModel(provider);

    String runId = UUID.randomUUID().toString();
    String containerName = "bench-gen-" + runId.substring(0, 8);
    long now = Instant.now().toEpochMilli();
    Path outputDir = Paths.get(props.getOutputBase(), instanceId + "-" + now);
    // Workspace lives under the (non-Documents) output base so a macOS TCC revocation on
    // ~/Documents can't freeze the worker on its own workspace mid-run (see benchmark/CLAUDE.md).
    Path workspace = Paths.get(props.getOutputBase(), "ws", runId).toAbsolutePath().normalize();
    try {
      Files.createDirectories(outputDir);
      Files.createDirectories(workspace);
    } catch (IOException ioe) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "failed to create dirs: " + ioe.getMessage());
    }

    acquireGenerationSlot(instanceId);
    try {
      setupCleanroom(image, workspace, containerName, runId, instance);
      // Minimal run row — the only state the code-gen path reads back: hardCapUsd (worker budget
      // cap) + sourceType="benchmark" (engine gate OFF for blind runs). NOT the full submitRun
      // lifecycle.
      PipelineRun row =
          PipelineRun.builder()
              .runId(runId)
              .userRequest(synthesizeBlindSpec(instance))
              .status("WORKING")
              .runMode(RunMode.PATCH)
              .sourceType("benchmark")
              .aiProvider(provider.toUpperCase())
              .plannerModel(model)
              .workerModel(model)
              .executionMode("PROTOTYPE")
              .createdByUserId(props.getBenchUserId())
              .orgId(props.getBenchOrgId())
              .hardCapUsd(DEFAULT_HARD_CAP_USD)
              .build();
      runRepo.save(row);

      log.info(
          "benchmark-gen START run={} instance={} provider={} model={}",
          runId,
          instanceId,
          provider,
          model);

      // THE code-gen call. CodeGenEngine.solve builds the ExecutionContext and dispatches the same
      // WorkerAgent → tools → LlmRouter loop as PATCH mode; the benchmark keeps its own
      // container-based captureDiff below for the scored patch. Gate OFF because sourceType is
      // "benchmark".
      EngineResult er =
          codeGenEngine.solve(
              EngineRequest.builder()
                  .taskSpec(synthesizeBlindSpec(instance))
                  .workspace(workspace)
                  .provider(provider)
                  .model(model)
                  .runId(runId)
                  .taskId("__root__")
                  .verifyMode(VerifyMode.NONE)
                  .build());

      String patch = captureDiff(containerName, instance.baseCommit(), workdirFor(instance));
      Path submission = outputDir.resolve("submission.patch");
      Files.writeString(submission, patch);
      Path preds = writePreds(outputDir, instanceId, model, patch);
      Path proPatches = null;
      if (instance.isSweBenchPro() || props.isSweBenchPro()) {
        proPatches = writeSweBenchProPatches(outputDir, instanceId, model, patch);
      }

      double cost = costTracker.snapshot(runId).costUsd();
      markTerminal(runId, er.outcome(), patch.length());

      log.info(
          "benchmark-gen END run={} instance={} outcome={} iters={} patch={}B cost=${}",
          runId,
          instanceId,
          er.outcome(),
          er.iterations(),
          patch.length(),
          String.format("%.3f", cost));

      return new GenerateResult(
          runId,
          instanceId,
          provider,
          model,
          er.outcome().name(),
          er.iterations(),
          er.toolCalls(),
          patch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
          cost,
          submission.toString(),
          preds.toString(),
          proPatches == null ? null : proPatches.toString());
    } catch (ResponseStatusException rse) {
      throw rse;
    } catch (Exception e) {
      log.error("benchmark-gen FAILED run={} instance={}", runId, instanceId, e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "generation failed: " + e.getMessage(), e);
    } finally {
      run("docker rm -f " + containerName, 30);
      containerStore.remove(runId);
      releaseGenerationSlot();
    }
  }

  void acquireGenerationSlot(String instanceId) {
    int limit = Math.max(1, props.getMaxConcurrentRuns());
    Semaphore slots;
    synchronized (generationSlotsLock) {
      if (generationSlots == null || generationSlotLimit != limit) {
        generationSlots = new Semaphore(limit, true);
        generationSlotLimit = limit;
      }
      slots = generationSlots;
    }
    try {
      boolean acquired =
          slots.tryAcquire(Math.max(0, props.getSubmitQueueWaitSeconds()), TimeUnit.SECONDS);
      if (!acquired) {
        throw new ResponseStatusException(
            HttpStatus.TOO_MANY_REQUESTS,
            "benchmark generation concurrency exhausted for "
                + instanceId
                + " (limit="
                + limit
                + ")");
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "interrupted waiting for benchmark generation slot");
    }
  }

  void releaseGenerationSlot() {
    Semaphore slots;
    synchronized (generationSlotsLock) {
      slots = generationSlots;
    }
    if (slots != null) {
      slots.release();
    }
  }

  /**
   * Pull (skip if present) → cp {@code /testbed/.} into the host workspace → run a live container
   * bind-mounting it at {@code /testbed} with {@code --network none} → register the container so
   * the worker's shell/build tools dispatch via {@code docker exec}. Mirrors {@link
   * the old async benchmark setup but called directly (no event), so setup is complete before {@code
   * CodeGenEngine.solve} invokes the worker.
   */
  private void setupCleanroom(
      String image,
      Path workspace,
      String containerName,
      String runId,
      SwebenchInstanceLoader.SwebenchInstance instance) {
    String workdir = workdirFor(instance);
    boolean present = run("docker image inspect " + image, 30).exit() == 0;
    if (!present && run("docker pull " + image, 1800).exit() != 0) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "docker pull failed for " + image);
    }
    String tempName = "bench-gen-prep-" + UUID.randomUUID().toString().substring(0, 8);
    if (run("docker create --name " + tempName + " " + image + " sleep 1", 60).exit() != 0) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "docker create failed for " + image);
    }
    try {
      if (run("docker cp " + tempName + ":" + workdir + "/. " + workspace, 600).exit() != 0) {
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "docker cp " + workdir + " failed");
      }
    } finally {
      run("docker rm -f " + tempName, 30);
    }
    String runCmd =
        String.format(
            "docker run -d --rm --network none --name %s -v %s:%s -w %s --entrypoint /bin/sh %s"
                + " -c 'sleep %dh'",
            containerName,
            workspace,
            workdir,
            workdir,
            image,
            props.getTaskWallTimeoutMinutes() / 60 + 1);
    ShellResult start = run(runCmd, 60);
    if (start.exit() != 0) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "docker run failed: " + start.output());
    }
    String[] lines = start.output().trim().split("\\r?\\n");
    String cid = "";
    for (int i = lines.length - 1; i >= 0; i--) {
      if (!lines[i].trim().isEmpty()) {
        cid = lines[i].trim();
        break;
      }
    }
    if (cid.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "docker run produced no container id");
    }
    containerStore.put(runId, cid, workdir);
    log.info("benchmark-gen container {} ({}) ready for {} ({})", containerName, cid, runId, image);
  }

  /** Capture the agent's {@code git diff} against the base commit, excluding run-infra noise. */
  private String captureDiff(String containerName, String baseCommit, String workdir) {
    String diffTarget = (baseCommit == null || baseCommit.isBlank()) ? "HEAD" : baseCommit;
    String excludes =
        " -- . \":(exclude).agent-context\" \":(exclude)Dockerfile\" \":(exclude).dockerignore\""
            + " \":(exclude)README.md\" \":(exclude)__pycache__\" \":(exclude).pytest_cache\""
            + " \":(exclude)*.egg-info\""
            + " \":(exclude,glob)**/pyproject.toml\" \":(exclude,glob)**/setup.py\""
            + " \":(exclude,glob)**/setup.cfg\" \":(exclude,glob)**/tox.ini\""
            + " \":(exclude,glob)**/MANIFEST.in\" \":(exclude,glob)**/requirements*.txt\""
            + " \":(exclude,glob)**/.github/**\""
            + " \":(exclude,glob)appendonlydir/**\""
            + " \":(exclude,glob)**/test_*.py\" \":(exclude,glob)**/*_test.py\""
            + " \":(exclude,glob)**/tests/**\" \":(exclude,glob)**/testing/**\""
            + " \":(exclude,glob)**/conftest.py\""
            + " \":(exclude,glob)**/repro*\" \":(exclude,glob)**/*_repro*\""
            + " \":(exclude,glob)tmp/**\" \":(exclude,glob)**/tmp_*\""
            + " \":(exclude,glob)**/scratch*\"";
    String cmd =
        "docker exec "
            + containerName
            + " sh -c '"
            + "cd "
            + workdir
            + " && "
            + "git ls-files --others --exclude-standard | xargs -r git add -N >/dev/null 2>&1; "
            + "git diff "
            + diffTarget
            + excludes
            + "'";
    ShellResult diff = run(cmd, 120);
    return diff.exit() == 0 ? diff.output() : "";
  }

  private Path writePreds(Path outputDir, String instanceId, String model, String patch)
      throws IOException {
    ObjectNode preds = JSON.createObjectNode();
    ObjectNode entry = preds.putObject(instanceId);
    entry.put("model_name_or_path", "executespec-benchmark/" + model);
    entry.put("instance_id", instanceId);
    entry.put("model_patch", patch);
    Path predsPath = outputDir.resolve("preds.json");
    Files.writeString(predsPath, preds.toPrettyString());
    return predsPath;
  }

  private Path writeSweBenchProPatches(
      Path outputDir, String instanceId, String model, String patch) throws IOException {
    ArrayNode entries = JSON.createArrayNode();
    ObjectNode entry = entries.addObject();
    entry.put("instance_id", instanceId);
    entry.put("patch", patch);
    entry.put("prefix", predictionPrefix(model));
    Path patchesPath = outputDir.resolve("swebench_pro_patches.json");
    Files.writeString(patchesPath, entries.toPrettyString());
    return patchesPath;
  }

  private String predictionPrefix(String model) {
    String configured = props.getPredictionPrefix();
    String prefix = configured == null || configured.isBlank() ? "executespec" : configured;
    if (model == null || model.isBlank()) return prefix;
    return prefix + "-" + model;
  }

  private void markTerminal(String runId, EngineOutcome outcome, int patchLen) {
    try {
      runRepo
          .findById(runId)
          .ifPresent(
              pr -> {
                pr.setStatus(patchLen > 0 ? "COMPLETE" : "PARTIAL");
                String reason = "BENCH_GEN:" + outcome.name();
                pr.setStatusReason(reason.length() > 80 ? reason.substring(0, 80) : reason);
                pr.setCompletedAt(Instant.now());
                runRepo.save(pr);
              });
    } catch (Exception e) {
      log.warn("benchmark-gen: failed to mark run {} terminal: {}", runId, e.getMessage());
    }
  }

  /**
   * PLANNING-tier defaults per provider (mirror {@code application.yml model-matrix} PLANNING row).
   * Overridable per request via {@code modelName}; this only sets the default when none is given so
   * the service stays dependency-light.
   */
  private static String defaultPlanningModel(String provider) {
    if (provider == null) return "claude-sonnet-5";
    return switch (provider.toLowerCase()) {
      case "openai" -> "gpt-5.4";
      case "gemini", "google" -> "gemini-3.5-flash";
      case "deepseek" -> "deepseek-v4-flash-native";
      default -> "claude-sonnet-5";
    };
  }

  /**
   * Pre-completeTask self-verification, provider-agnostic. Grounded in observed blind-run failures:
   * a changed return/arg signature with an un-updated caller (Python "too many values to unpack",
   * Go "undefined: X" / "build failed") and incomplete cross-call-site edits. This does not reveal
   * the held-out tests — it only enforces internal consistency of the candidate patch.
   */
  private static final String BENCH_VERIFY_CHECKLIST =
      "6. BEFORE completeTask, self-verify the patch is internally consistent:\n"
          + "   - If you changed any function/method signature or return shape, searchFiles for"
          + " EVERY caller and update them; a missed caller fails with unpack/arg-count/undefined"
          + " errors.\n"
          + "   - Confirm the files you edited still parse/compile (build or import the changed"
          + " module); a patch that does not compile scores zero regardless of logic.\n"
          + "   - Re-read the issue's stated expected behavior and confirm your change produces"
          + " exactly that, not merely a plausible-looking edit.\n";

  /** Blind spec — issue + offline/existing-repo framing only. NO held-out tests. */
  private String synthesizeBlindSpec(SwebenchInstanceLoader.SwebenchInstance i) {
    StringBuilder sb = new StringBuilder();
    String language = i.repoLanguage();
    if (language == null || language.isBlank()) language = "software";
    sb.append("Resolve the following GitHub issue in an existing ")
        .append(language)
        .append(" repository.\n\n");
    sb.append("# Environment\n");
    sb.append("- You are working in an EXISTING repository already checked out at ")
        .append(workdirFor(i))
        .append(
            " (your workspace). Investigate the existing code and fix the issue. Do NOT scaffold a"
                + " new project.\n");
    sb.append(
        "- The environment is offline (no network) and all dependencies are pre-installed. Do not"
            + " pip install / curl / git fetch.\n");
    sb.append(
            "- Edit only non-test source files. Do NOT modify or add test files. Your submission is"
                + " the git diff of ")
        .append(workdirFor(i))
        .append(" against its base commit.\n\n");
    sb.append("# Repository\n- Repo: ")
        .append(i.repo())
        .append("\n- Working directory: ")
        .append(workdirFor(i))
        .append("\n");
    if (i.interfaceName() != null && !i.interfaceName().isBlank()) {
      sb.append("- Interface: ").append(i.interfaceName()).append("\n");
    }
    sb.append("\n");
    sb.append("# Issue\n\n").append(i.problemStatement()).append("\n\n");
    sb.append("# How to work\n");
    sb.append(
        "1. Investigate with listFiles / searchFiles / readFile to locate the relevant source.\n");
    sb.append(
        "2. Pin down the EXACT expected behavior BEFORE editing, and check whether the fix belongs"
            + " in a shared mechanism (callers, serialization/storage layers, sibling modules) vs."
            + " a single call site — match the codebase's conventions.\n");
    if (i.interfaceName() != null && !i.interfaceName().isBlank()) {
      sb.append(
          "3. The Interface above is a contract, not a complete file list. Inspect every named"
              + " path, then search for production callers/consumers of those methods/functions and"
              + " patch all code required for the issue's visible behavior. Do not stop after only"
              + " adding helper methods if controllers, sockets, templates, schemas, or messages"
              + " must use them.\n");
      sb.append("4. Make a minimal, targeted fix across the required production files.\n");
      sb.append(
          "5. Run the repository's EXISTING tests for the module you changed to confirm no"
              + " regression, then call completeTask.\n");
      sb.append(BENCH_VERIFY_CHECKLIST);
    } else {
      sb.append("3. Make a minimal, targeted fix.\n");
      sb.append(
          "4. Run the repository's EXISTING tests for the module you changed to confirm no"
              + " regression, then call completeTask.\n");
      sb.append(BENCH_VERIFY_CHECKLIST);
    }
    return sb.toString();
  }

  private String workdirFor(SwebenchInstanceLoader.SwebenchInstance instance) {
    return (instance.isSweBenchPro() || props.isSweBenchPro()) ? "/app" : "/testbed";
  }

  private record ShellResult(int exit, String output) {}

  private ShellResult run(String command, int timeoutSec) {
    try {
      ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      StringBuilder output = new StringBuilder();
      Thread reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      output.append(
                          new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                      // The caller gets the process exit/timeout status; output is best effort.
                    }
                  });
      boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
      if (!done) {
        p.destroyForcibly();
        reader.join(1000);
        return new ShellResult(-1, "timeout after " + timeoutSec + "s");
      }
      reader.join(1000);
      return new ShellResult(p.exitValue(), output.toString());
    } catch (IOException ioe) {
      return new ShellResult(-2, "io error: " + ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return new ShellResult(-3, "interrupted");
    }
  }
}
