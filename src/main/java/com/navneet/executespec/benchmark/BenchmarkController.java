package com.navneet.executespec.benchmark;

import java.util.Map;

import com.navneet.executespec.swebench.SwebenchInstanceLoader;
import com.navneet.executespec.swebench.SwebenchProperties;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin-only benchmark runner — runs SWE-bench instances through the REAL production pipeline
 * (PATCH run-mode), blind (see {@link BenchmarkRunService}). SUPER_ADMIN only. Disabled unless
 * {@code swebench.enabled=true}.
 *
 * <p>Distinct from the legacy {@code /api/admin/swebench/*} controller (a bespoke oracle-mode
 * adapter that injected the gold test), which is superseded and will be removed once this path is
 * validated.
 */
@RestController
@RequestMapping("/api/admin/benchmark")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Slf4j
public class BenchmarkController {

  private final BenchmarkRunService runService;
  private final BenchmarkGenerationService generationService;
  private final SwebenchInstanceLoader loader;
  private final SwebenchProperties props;

  public record SubmitRequest(
      String instanceId,
      String aiProvider,
      String modelName,
      // RL-2026-05-066 (project-mode eval): optional per-instance project + its repo, so the run
      // gets the project's codemap/arch/RAG injected. Null/absent → blind (the bench project).
      String projectId,
      java.util.List<String> selectedRepoIds) {}

  /** Submit one instance for a blind PATCH-mode run through the production pipeline. */
  @PostMapping("/run")
  public ResponseEntity<BenchmarkRunService.TaskSubmitResult> submit(
      @RequestBody SubmitRequest req) {
    log.info(
        "benchmark submit instance={} provider={} model={}",
        req.instanceId(),
        req.aiProvider(),
        req.modelName());
    return ResponseEntity.accepted()
        .body(
            runService.submit(
                req.instanceId(),
                req.aiProvider(),
                req.modelName(),
                req.projectId(),
                req.selectedRepoIds()));
  }

  /**
   * Lean, SYNCHRONOUS code-generation. Reuses the production code-gen engine ({@code
   * CodeGenEngineImpl} → worker → tools) directly — no {@code submitRun} lifecycle, no async, no
   * listeners. Blocks until the worker terminates (~2–4 min for a Live instance) and returns the
   * captured patch + cost. See {@link BenchmarkGenerationService}.
   */
  @PostMapping("/generate")
  public ResponseEntity<BenchmarkGenerationService.GenerateResult> generate(
      @RequestBody SubmitRequest req) {
    log.info(
        "benchmark generate instance={} provider={} model={}",
        req.instanceId(),
        req.aiProvider(),
        req.modelName());
    return ResponseEntity.ok(
        generationService.generate(req.instanceId(), req.aiProvider(), req.modelName()));
  }

  /** Read-only preview — what image / repo / base commit this instance resolves to. */
  @GetMapping("/preview/{instanceId}")
  public ResponseEntity<Map<String, Object>> preview(@PathVariable String instanceId) {
    SwebenchInstanceLoader.SwebenchInstance i = loader.findById(instanceId);
    return ResponseEntity.ok(
        Map.of(
            "instanceId", i.instanceId(),
            "repo", i.repo(),
            "baseCommit", i.baseCommit(),
            "image", loader.imageNameFor(instanceId),
            "problemStatementChars", i.problemStatement().length(),
            "enabled", String.valueOf(props.isEnabled()),
            "benchProjectId", props.getBenchProjectId()));
  }

  /** List loaded instance ids. */
  @GetMapping("/instances")
  public ResponseEntity<Map<String, Object>> listInstances() {
    return ResponseEntity.ok(Map.of("count", loader.allInstanceIds().size()));
  }
}
