package com.navneet.executespec.swebench;

import java.util.Map;

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
 * Admin-only SWE-bench task runner. SUPER_ADMIN auth required.
 *
 * <p>Each instance submitted creates a real {@code PipelineRun} in the synthetic SWE-bench Bench
 * project, so it shows up in the UI under that project just like a normal customer run — but the
 * worker tools dispatch into a SWE-bench task container with the repo at {@code /testbed}.
 *
 * <p>Disabled by default ({@code swebench.enabled=false}); 503 when off.
 */
@RestController
@RequestMapping("/api/admin/swebench")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Slf4j
public class SwebenchController {

  private final SwebenchTaskService taskService;
  private final SwebenchInstanceLoader loader;
  private final SwebenchProperties props;

  public record SubmitRequest(String instanceId, String aiProvider, String modelName) {}

  /** Submit a SWE-bench instance for execution. */
  @PostMapping("/run")
  public ResponseEntity<SwebenchTaskService.TaskSubmitResult> submit(
      @RequestBody SubmitRequest req) {
    log.info(
        "SWE-bench submit instance={} provider={} model={}",
        req.instanceId(),
        req.aiProvider(),
        req.modelName());
    return ResponseEntity.accepted()
        .body(taskService.submit(req.instanceId(), req.aiProvider(), req.modelName()));
  }

  /** Read-only — what image / repo / base commit would this instance resolve to. */
  @GetMapping("/preview/{instanceId}")
  public ResponseEntity<Map<String, Object>> preview(@PathVariable String instanceId) {
    SwebenchInstanceLoader.SwebenchInstance i = loader.findById(instanceId);
    return ResponseEntity.ok(
        Map.of(
            "instanceId", i.instanceId(),
            "repo", i.repo(),
            "baseCommit", i.baseCommit(),
            "image", loader.imageNameFor(instanceId),
            "failToPassCount", i.failToPass().size(),
            "passToPassCount", i.passToPass().size(),
            "problemStatementChars", i.problemStatement().length(),
            "enabled", String.valueOf(props.isEnabled()),
            "benchProjectId", props.getBenchProjectId()));
  }

  /** List all loaded instance IDs. */
  @GetMapping("/instances")
  public ResponseEntity<Map<String, Object>> listInstances() {
    return ResponseEntity.ok(Map.of("count", loader.allInstanceIds().size()));
  }
}
