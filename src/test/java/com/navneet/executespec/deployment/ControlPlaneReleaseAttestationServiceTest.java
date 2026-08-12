package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.config.featureflag.FeatureFlag;
import com.navneet.executespec.config.featureflag.FeatureFlagRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sandbox.RunSandboxRuntimeDispatcher;
import com.navneet.executespec.sandbox.SandboxProperties;
import com.navneet.executespec.workspacegeneration.BuildProtocolVersionService;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationSchemaAttestor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlPlaneReleaseAttestationServiceTest {

  private static final String SHA = "a".repeat(64);
  private final WorkspaceGenerationSchemaAttestor schemaAttestor =
      mock(WorkspaceGenerationSchemaAttestor.class);
  private final ControlPlaneSchemaEvidenceExporter schemaExporter =
      mock(ControlPlaneSchemaEvidenceExporter.class);
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final FeatureFlagRepository flagRepository = mock(FeatureFlagRepository.class);
  private final SandboxProperties sandbox = sandboxProperties();

  @BeforeEach
  void readySchemaAndMatchingProcessFlags() {
    var schema =
        new WorkspaceGenerationSchemaAttestor.Attestation(
            1, Instant.EPOCH, true, "399", 399, List.of(), true, null);
    when(schemaAttestor.attest()).thenReturn(schema);
    when(schemaExporter.export(schema))
        .thenReturn(
            new ControlPlaneSchemaEvidenceExporter.Evidence(
                List.of(
                    new ControlPlaneSchemaEvidenceExporter.AppliedMigration(
                        1, "399", "fixture", "SQL", "V399__fixture.sql", 123, Instant.EPOCH, true)),
                List.of(),
                true));
    when(flags.getString(BuildProtocolVersionService.SHARED_KERNEL_ADMISSION_FLAG, "__MISSING__"))
        .thenReturn("false");
    when(flags.getString(RunSandboxRuntimeDispatcher.FLAG_DISPATCH, "__MISSING__"))
        .thenReturn("false");
    when(flags.getString(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1, "__MISSING__"))
        .thenReturn("false");
    when(flags.getString(
            ControlPlaneReleaseAttestationService.REQUIREMENT_GATE_MODE_FLAG, "__MISSING__"))
        .thenReturn("LEGACY");
    persistedFlag(BuildProtocolVersionService.SHARED_KERNEL_ADMISSION_FLAG, "false", "BOOLEAN");
    persistedFlag(RunSandboxRuntimeDispatcher.FLAG_DISPATCH, "false", "BOOLEAN");
    persistedFlag(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1, "false", "BOOLEAN");
    persistedFlag(
        ControlPlaneReleaseAttestationService.REQUIREMENT_GATE_MODE_FLAG, "LEGACY", "STRING");
  }

  @Test
  void bindsOneCompleteStableDarkReleaseTuple() {
    var service = service(SHA, SHA, "0.9.13", "0.3.11", "1.2.0");

    var first = service.attest();
    var second = service.attest();

    assertThat(first.evidenceComplete()).isTrue();
    assertThat(first.safeDarkState()).isTrue();
    assertThat(first.reasons()).isEmpty();
    assertThat(first.release().runningSha()).isEqualTo("b".repeat(40));
    assertThat(first.schema().exported().appliedMigrations()).hasSize(1);
    assertThat(first.featureFlags()).hasSize(4).allMatch(flag -> flag.processParity());
    assertThat(first.runtime().imagePinnedByDigest()).isTrue();
    assertThat(first.graph().complete()).isTrue();
    assertThat(first.bundleSha256()).hasSize(64).isEqualTo(second.bundleSha256());
  }

  @Test
  void incompleteGraphRuntimeAndFlagDriftFailClosedWithExactReasons() {
    sandbox.setImage("docker.io/library/debian:stable-slim");
    when(flags.getString(BuildProtocolVersionService.SHARED_KERNEL_ADMISSION_FLAG, "__MISSING__"))
        .thenReturn("true");

    var result = service("", "", "", "", "").attest();

    assertThat(result.evidenceComplete()).isFalse();
    assertThat(result.safeDarkState()).isFalse();
    assertThat(result.reasons())
        .contains(
            "GRAPH_PROVENANCE_INCOMPLETE",
            "SANDBOX_IMAGE_NOT_DIGEST_PINNED",
            "FEATURE_FLAG_PROCESS_DRIFT:build.shared-code-change-loop.enabled",
            "SHARED_BUILD_ADMISSION_ENABLED");
  }

  private ControlPlaneReleaseAttestationService service(
      String graphSha,
      String manifestSha,
      String graphifyVersion,
      String sqlParserVersion,
      String hclParserVersion) {
    return new ControlPlaneReleaseAttestationService(
        new RunningReleaseIdentityService("b".repeat(40), "c".repeat(64), "deploy-1"),
        schemaAttestor,
        schemaExporter,
        flags,
        flagRepository,
        sandbox,
        "/var/lib/executespec/workspaces",
        "LEGACY",
        graphSha,
        manifestSha,
        graphifyVersion,
        sqlParserVersion,
        hclParserVersion);
  }

  private void persistedFlag(String key, String value, String type) {
    when(flagRepository.findByFlagKey(key))
        .thenReturn(
            Optional.of(
                FeatureFlag.builder()
                    .flagKey(key)
                    .flagValue(value)
                    .valueType(FeatureFlag.ValueType.valueOf(type))
                    .updatedAt(Instant.EPOCH)
                    .updatedBy("fixture")
                    .build()));
  }

  private static SandboxProperties sandboxProperties() {
    SandboxProperties properties = new SandboxProperties();
    properties.setImage("docker.io/library/debian@sha256:" + "d".repeat(64));
    properties.setWorkspaceRoot("/var/lib/executespec/sandboxes");
    properties.setRuntimeUid(65532);
    properties.setRuntimeGid(65532);
    properties.setCpus(2.0);
    properties.setMemoryBytes(2L * 1024 * 1024 * 1024);
    properties.setPids(256);
    properties.setTmpfsBytes(256L * 1024 * 1024);
    properties.setWallClock(Duration.ofMinutes(30));
    return properties;
  }
}
