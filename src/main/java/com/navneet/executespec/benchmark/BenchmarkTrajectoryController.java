package com.navneet.executespec.benchmark;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import lombok.RequiredArgsConstructor;

/** Benchmark-only, run-read-guarded complete trajectory export. */
@Profile({"local", "dev", "benchmark"})
@RestController
@RequestMapping("/api/v2/test/benchmark/runs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BenchmarkTrajectoryController {

  private final BenchmarkTrajectoryStore store;
  private final RunAccessGuard runAccessGuard;

  @GetMapping("/{runId}/trajectory")
  public ResponseEntity<StreamingResponseBody> export(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails user) {
    runAccessGuard.requireRunAccess(runId, user);
    final BenchmarkTrajectoryStore.ExportSnapshot snapshot;
    try {
      snapshot = store.prepareExport(runId);
    } catch (BenchmarkTrajectoryStore.MissingTrajectoryException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (BenchmarkTrajectoryStore.IncompleteTrajectoryException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    StreamingResponseBody body = output -> store.copyTo(snapshot, output);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(runId + "-trajectory.ndjson")
                .build()
                .toString())
        .header("X-Trajectory-Sha256", snapshot.sha256())
        .header("X-Trajectory-Event-Count", String.valueOf(snapshot.eventCount()))
        .header("X-Trajectory-Truncated", "false")
        .header("X-Trajectory-Retained-Until", snapshot.retainedUntil().toString())
        .contentType(MediaType.parseMediaType("application/x-ndjson"))
        .contentLength(snapshot.sizeBytes())
        .body(body);
  }
}
