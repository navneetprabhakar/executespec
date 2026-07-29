package com.navneet.executespec.integration;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import lombok.extern.slf4j.Slf4j;

/** Resolves the bounded engineering-standards context injected into run planning. */
@Slf4j
final class RunStandardsPromptResolver {

  private static final int INJECT_CAP = 8_000;

  private RunStandardsPromptResolver() {}

  static String resolve(
      FeatureFlagService featureFlags,
      RunStandardsEnforcer standardsEnforcer,
      String runId,
      String projectId,
      Path workspaceRoot,
      List<UUID> selectedRepoIds) {
    if (projectId == null || !featureFlags.isEnabled("standards.enforcement.enabled")) return "";
    try {
      String block =
          RunPipelineSupport.truncate(
              standardsEnforcer.composePromptBlock(projectId, workspaceRoot, selectedRepoIds),
              INJECT_CAP);
      if (!block.isBlank()) {
        log.info(
            "V2 run={} injected engineering standards into agent context ({} chars)",
            runId,
            block.length());
      }
      return block;
    } catch (Exception e) {
      log.warn(
          "V2 run={} standards prompt injection unavailable: {}",
          runId,
          e.getClass().getSimpleName());
      return "";
    }
  }
}
