package com.navneet.executespec.workspacegeneration;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Selects BUILD protocol only at admission and reads only its immutable durable value afterward.
 */
@Service
@RequiredArgsConstructor
public class BuildProtocolVersionService {

  public static final String SHARED_KERNEL_ADMISSION_FLAG = "build.shared-code-change-loop.enabled";

  private final PipelineRunRepository runs;
  private final FeatureFlagService featureFlags;
  private final WorkspaceGenerationSchemaAttestor schemaAttestor;

  /** The only live-flag read: called while constructing the initial run INSERT. */
  public String selectForSubmission(String runMode) {
    if (!RunMode.BUILD.equals(runMode)) return null;
    if (!featureFlags.isEnabled(SHARED_KERNEL_ADMISSION_FLAG)) {
      return BuildProtocolVersion.LEGACY_V1.name();
    }
    schemaAttestor.requireReady();
    return BuildProtocolVersion.SHARED_KERNEL_V2.name();
  }

  public BuildProtocolVersion requireExecutable(String runId) {
    String persisted =
        runs.findById(runId)
            .orElseThrow(() -> new IllegalStateException("BUILD_PROTOCOL_RUN_MISSING"))
            .getBuildProtocolVersion();
    BuildProtocolVersion version = BuildProtocolVersion.parse(persisted);
    version.requireExecutable();
    return version;
  }
}
