package com.navneet.executespec.integration;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.navneet.executespec.tool.JobRegistry;

import org.junit.jupiter.api.Test;

class RunResourceCleanerTest {

  @Test
  void cleanupRunDelegatesToJobRegistry() {
    JobRegistry jobRegistry = mock(JobRegistry.class);
    RunResourceCleaner cleaner = new RunResourceCleaner(jobRegistry);

    cleaner.cleanupRun("run-1");

    verify(jobRegistry).cleanup("run-1");
  }

  @Test
  void cleanupRunSwallowsCleanupFailures() {
    JobRegistry jobRegistry = mock(JobRegistry.class);
    doThrow(new IllegalStateException("boom")).when(jobRegistry).cleanup("run-2");
    RunResourceCleaner cleaner = new RunResourceCleaner(jobRegistry);

    cleaner.cleanupRun("run-2");

    verify(jobRegistry).cleanup("run-2");
  }
}
