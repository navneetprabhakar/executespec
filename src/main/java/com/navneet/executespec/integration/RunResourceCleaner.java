package com.navneet.executespec.integration;

import com.navneet.executespec.tool.JobRegistry;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Best-effort run-end cleanup for resources that outlive a single tool call. */
@Service
@Slf4j
@RequiredArgsConstructor
public class RunResourceCleaner {

  private final JobRegistry jobRegistry;

  public void cleanupRun(String runId) {
    try {
      jobRegistry.cleanup(runId);
    } catch (Exception e) {
      log.warn("Run resource cleanup failed for run={}: {}", runId, e.getMessage());
    }
  }
}
