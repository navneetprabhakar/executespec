package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Pins the reviewer-to-validation boundary as read-only unless a planned correction owns edits. */
class RunPostReviewWorkspaceMutationBoundaryTest {

  @Test
  void initialAndResumePathsDoNotSynthesizeAReadmeAfterReview() throws Exception {
    String initial = source("integration/RunOrchestrator.java");
    String resume = source("integration/RunLifecycleService.java");
    String composer = source("integration/RunContextComposer.java");

    assertThat(initial).doesNotContain("contextComposer.ensureReadme(");
    assertThat(resume).doesNotContain("contextComposer.ensureReadme(");
    assertThat(composer).doesNotContain("void ensureReadme(", "String renderRunReadme(");
  }

  private static String source(String relative) throws Exception {
    return Files.readString(Path.of("src/main/java/com/navneet/executespec").resolve(relative));
  }
}
