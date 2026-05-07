package com.navneet.executespec.programbench;

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
 * Admin-only ProgramBench task runner. SUPER_ADMIN auth required.
 *
 * <p>Each task submitted creates a real {@code PipelineRun} in the synthetic ProgramBench Bench
 * project, so it shows up in the UI under that project just like a normal customer run — but the
 * worker tools dispatch into a programbench cleanroom container instead of the host workspace.
 *
 * <p>Disabled by default ({@code programbench.enabled=false}); 503 when off.
 */
@RestController
@RequestMapping("/api/admin/programbench")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Slf4j
public class ProgramBenchController {

  private final ProgramBenchTaskService taskService;
  private final ProgramBenchProperties props;

  public record SubmitRequest(String taskId, String aiProvider, String modelName) {}

  /**
   * Submit a programbench task for execution. Returns the runId so the caller can navigate to
   * {@code /runs/<runId>} in the UI.
   */
  @PostMapping("/run")
  public ResponseEntity<ProgramBenchTaskService.TaskSubmitResult> submit(
      @RequestBody SubmitRequest req) {
    log.info(
        "ProgramBench submit task={} provider={} model={}",
        req.taskId(),
        req.aiProvider(),
        req.modelName());
    return ResponseEntity.accepted()
        .body(taskService.submit(req.taskId(), req.aiProvider(), req.modelName()));
  }

  /**
   * Read-only — what task ID / image would this task resolve to. Useful sanity check before
   * spending money.
   */
  @GetMapping("/preview/{taskId}")
  public ResponseEntity<Map<String, String>> preview(@PathVariable String taskId) {
    String image = props.getDockerOrg() + "/" + taskId.replace("__", "_1776_") + ":task_cleanroom";
    return ResponseEntity.ok(
        Map.of(
            "taskId",
            taskId,
            "image",
            image,
            "enabled",
            String.valueOf(props.isEnabled()),
            "benchProjectId",
            props.getBenchProjectId()));
  }
}
