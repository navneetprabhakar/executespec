package com.navneet.executespec.integration.scm;

import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.workspacegeneration.BuildProtocolVersion;

/** Stable protocol predicates used by publication admission and preparation. */
final class PublicationAdmissionPolicy {

  private PublicationAdmissionPolicy() {}

  static boolean admitsExecutedTestScope(String scope) {
    return "WORKSPACE_TEST_SUITE".equals(scope) || "LIVE_PREVIEW_FUNCTIONAL".equals(scope);
  }

  static boolean requiresPreparedArtifacts(PipelineRun run) {
    return RunMode.BUILD.equals(run.getRunMode())
        && BuildProtocolVersion.SHARED_KERNEL_V2.name().equals(run.getBuildProtocolVersion());
  }
}
