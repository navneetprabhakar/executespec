package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Pins the whole-project callers to durable admission and completion-aware semantics. */
class ProjectArchitectureDurableCallerContractTest {

  @Test
  void noCustomerWholeProjectCallerBypassesDurableJobProtocol() throws Exception {
    String controller = source("codeintel/ProjectArchitectureController.java");
    String confirmed = source("specchat/ProjectChatConfirmedActionExecutor.java");
    String legacyTool = source("memory/chattools/ProjectChatTools.java");
    String postMerge = source("project/pr/PostMergeCascadeService.java");
    String execution = source("execution/service/ExecutionOrchestrator.java");
    String run = source("integration/RunOrchestrator.java");

    assertThat(controller).doesNotContain("service.generate(projectId");
    assertThat(confirmed).contains("architectureJobs.requestExact(");
    assertThat(legacyTool).contains("architectureJobs.request(");
    assertThat(postMerge).contains("architectureJobs.request(");
    assertThat(execution).contains("requestAndAwaitArchitectureId(");
    assertThat(run).contains("requestWithProviderAndAwaitArchitectureId(");
    assertThat(run).doesNotContain("projectArchitectureService.generate(");
  }

  private static String source(String relative) throws Exception {
    return Files.readString(Path.of("src/main/java/com/navneet/executespec").resolve(relative));
  }
}
