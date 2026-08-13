package com.navneet.executespec.integration;

import java.util.function.Function;

import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.DisposableVerificationWorkspaceService;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationRecoveryService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** One orchestration seam for read-only work against a disposable accepted generation. */
@Component
@RequiredArgsConstructor
@Slf4j
class AcceptedGenerationVerificationRunner {

  private final DisposableVerificationWorkspaceService workspaces;
  private final WorkspaceGenerationRecoveryService recovery;

  <T> T execute(
      ExecutionContext canonicalContext, String phase, Function<ExecutionContext, T> verification) {
    String runId = canonicalContext.runId();
    DisposableVerificationWorkspaceService.Execution<T> execution =
        workspaces.executeAccepted(
            canonicalContext,
            () -> recovery.assertAcceptedFrontier(runId, canonicalContext.workspaceRoot(), phase),
            verification);
    log.info(
        "RunOrchestrator run={} phase={} disposable side-effect entries={}",
        runId,
        phase,
        execution.sideEffectEntryCount());
    return execution.value();
  }
}
