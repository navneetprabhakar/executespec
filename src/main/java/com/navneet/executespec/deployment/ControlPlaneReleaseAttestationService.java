package com.navneet.executespec.deployment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.navneet.executespec.config.featureflag.FeatureFlag;
import com.navneet.executespec.config.featureflag.FeatureFlagRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sandbox.RunSandboxRuntimeDispatcher;
import com.navneet.executespec.sandbox.SandboxProperties;
import com.navneet.executespec.workspacegeneration.BuildProtocolVersion;
import com.navneet.executespec.workspacegeneration.BuildProtocolVersionService;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationSchemaAttestor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Builds one read-only, digest-bound qualification tuple for the running BUILD control plane. */
@Service
public class ControlPlaneReleaseAttestationService {

  static final String REQUIREMENT_GATE_MODE_FLAG = "requirement.ir.gate.mode";
  static final String BUNDLE_AUTHORITY = "READ_ONLY_RUNNING_RELEASE_SNAPSHOT_V1";
  private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
  private static final Pattern PINNED_IMAGE =
      Pattern.compile("(?:.+@)?sha256:[a-f0-9]{64}", Pattern.CASE_INSENSITIVE);
  private static final List<String> FLAG_KEYS =
      List.of(
          BuildProtocolVersionService.SHARED_KERNEL_ADMISSION_FLAG,
          RunSandboxRuntimeDispatcher.FLAG_DISPATCH,
          RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1,
          REQUIREMENT_GATE_MODE_FLAG);

  private final RunningReleaseIdentityService releaseIdentities;
  private final WorkspaceGenerationSchemaAttestor schemaAttestor;
  private final ControlPlaneSchemaEvidenceExporter schemaExporter;
  private final FeatureFlagService featureFlags;
  private final FeatureFlagRepository featureFlagRepository;
  private final SandboxProperties sandbox;
  private final String agentWorkspaceRoot;
  private final String llmOperationAdmissionMode;
  private final GraphEvidence graph;

  @Autowired
  public ControlPlaneReleaseAttestationService(
      RunningReleaseIdentityService releaseIdentities,
      WorkspaceGenerationSchemaAttestor schemaAttestor,
      ControlPlaneSchemaEvidenceExporter schemaExporter,
      FeatureFlagService featureFlags,
      FeatureFlagRepository featureFlagRepository,
      SandboxProperties sandbox,
      @Value("${agent.workspace}") String agentWorkspaceRoot,
      @Value("${executespec.llm.operation-admission.mode:LEGACY}") String llmOperationAdmissionMode,
      @Value("${EXECUTESPEC_CONTROL_PLANE_GRAPH_SHA256:}") String graphSha256,
      @Value("${EXECUTESPEC_CONTROL_PLANE_GRAPH_MANIFEST_SHA256:}") String graphManifestSha256,
      @Value("${EXECUTESPEC_GRAPHIFY_VERSION:}") String graphifyVersion,
      @Value("${EXECUTESPEC_GRAPHIFY_SQL_PARSER_VERSION:}") String sqlParserVersion,
      @Value("${EXECUTESPEC_GRAPHIFY_HCL_PARSER_VERSION:}") String hclParserVersion) {
    this.releaseIdentities = releaseIdentities;
    this.schemaAttestor = schemaAttestor;
    this.schemaExporter = schemaExporter;
    this.featureFlags = featureFlags;
    this.featureFlagRepository = featureFlagRepository;
    this.sandbox = sandbox;
    this.agentWorkspaceRoot = normalize(agentWorkspaceRoot);
    this.llmOperationAdmissionMode = normalize(llmOperationAdmissionMode).toUpperCase(Locale.ROOT);
    this.graph =
        new GraphEvidence(
            normalizeLower(graphSha256),
            normalizeLower(graphManifestSha256),
            normalize(graphifyVersion),
            normalize(sqlParserVersion),
            normalize(hclParserVersion),
            validSha(graphSha256)
                && validSha(graphManifestSha256)
                && !normalize(graphifyVersion).isBlank()
                && !normalize(sqlParserVersion).isBlank()
                && !normalize(hclParserVersion).isBlank());
  }

  /**
   * Uses a repeatable-read transaction so Flyway history and pg_catalog definitions come from one
   * database snapshot. Process environment and feature-cache values are captured alongside their
   * persisted counterparts; parity is explicit rather than inferred.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Attestation attest() {
    Instant checkedAt = Instant.now();
    List<String> reasons = new ArrayList<>();

    ReleaseEvidence release = releaseEvidence(reasons);
    SchemaEvidence schema = schemaEvidence(reasons);
    List<FlagEvidence> flags = flagEvidence(reasons);
    ProtocolEvidence protocol =
        new ProtocolEvidence(
            BuildProtocolVersion.SHARED_KERNEL_V2.name(), llmOperationAdmissionMode);
    RuntimeEvidence runtime = runtimeEvidence(reasons);

    if (!graph.complete()) reasons.add("GRAPH_PROVENANCE_INCOMPLETE");
    boolean flagsComplete =
        flags.size() == FLAG_KEYS.size()
            && flags.stream().allMatch(flag -> flag.persisted() && flag.processParity());
    boolean evidenceComplete =
        release.complete()
            && schema.complete()
            && flagsComplete
            && runtime.complete()
            && graph.complete();
    boolean sharedBuildEnabled =
        flagValue(flags, BuildProtocolVersionService.SHARED_KERNEL_ADMISSION_FLAG)
            .map(Boolean::parseBoolean)
            .orElse(false);
    boolean safeDarkState = evidenceComplete && !sharedBuildEnabled;
    if (sharedBuildEnabled) reasons.add("SHARED_BUILD_ADMISSION_ENABLED");

    List<String> normalizedReasons = reasons.stream().distinct().sorted().toList();
    String digest =
        bundleDigest(release, schema, flags, protocol, runtime, graph, normalizedReasons);
    return new Attestation(
        1,
        checkedAt,
        BUNDLE_AUTHORITY,
        release,
        schema,
        flags,
        protocol,
        runtime,
        graph,
        evidenceComplete,
        safeDarkState,
        normalizedReasons,
        digest);
  }

  private ReleaseEvidence releaseEvidence(List<String> reasons) {
    try {
      RunningReleaseIdentityService.ReleaseIdentity identity = releaseIdentities.read();
      return new ReleaseEvidence(
          identity.runningSha(),
          identity.artifactSha256(),
          identity.deploymentAttemptId(),
          identity.authority(),
          true);
    } catch (RuntimeException unavailable) {
      reasons.add("RUNNING_RELEASE_IDENTITY_INVALID");
      return new ReleaseEvidence(null, null, null, null, false);
    }
  }

  private SchemaEvidence schemaEvidence(List<String> reasons) {
    WorkspaceGenerationSchemaAttestor.Attestation attestation = schemaAttestor.attest();
    if (!attestation.ready()) reasons.add("SCHEMA_ATTESTATION_INCOMPLETE");
    if (!attestation.databaseReachable()) {
      return new SchemaEvidence(attestation, null, false);
    }
    try {
      ControlPlaneSchemaEvidenceExporter.Evidence exported = schemaExporter.export(attestation);
      if (!exported.complete()) reasons.add("SCHEMA_EXPORT_INCOMPLETE");
      return new SchemaEvidence(attestation, exported, exported.complete());
    } catch (DataAccessException unavailable) {
      reasons.add("SCHEMA_EXPORT_UNAVAILABLE");
      return new SchemaEvidence(attestation, null, false);
    }
  }

  private List<FlagEvidence> flagEvidence(List<String> reasons) {
    Map<String, PersistedFlag> persisted = new LinkedHashMap<>();
    try {
      for (String key : FLAG_KEYS) {
        featureFlagRepository
            .findByFlagKey(key)
            .map(ControlPlaneReleaseAttestationService::persistedFlag)
            .ifPresent(flag -> persisted.put(flag.key(), flag));
      }
    } catch (DataAccessException unavailable) {
      reasons.add("FEATURE_FLAG_SNAPSHOT_UNAVAILABLE");
    }

    List<FlagEvidence> result = new ArrayList<>(FLAG_KEYS.size());
    FLAG_KEYS.stream()
        .sorted()
        .forEach(
            key -> {
              PersistedFlag row = persisted.get(key);
              String effective = featureFlags.getString(key, "__MISSING__");
              boolean parity = row != null && row.value().equals(effective);
              if (row == null) reasons.add("FEATURE_FLAG_MISSING:" + key);
              else if (!parity) reasons.add("FEATURE_FLAG_PROCESS_DRIFT:" + key);
              result.add(
                  new FlagEvidence(
                      key,
                      row != null,
                      row == null ? null : row.value(),
                      effective,
                      row == null ? null : row.valueType(),
                      row == null ? null : row.updatedAt(),
                      row == null ? null : row.updatedBy(),
                      parity));
            });
    return List.copyOf(result);
  }

  private static PersistedFlag persistedFlag(FeatureFlag flag) {
    return new PersistedFlag(
        flag.getFlagKey(),
        flag.getFlagValue(),
        flag.getValueType() == null ? null : flag.getValueType().name(),
        flag.getUpdatedAt(),
        flag.getUpdatedBy());
  }

  private RuntimeEvidence runtimeEvidence(List<String> reasons) {
    boolean imagePinned = PINNED_IMAGE.matcher(normalize(sandbox.getImage())).matches();
    boolean sandboxRootAbsolute = absolute(sandbox.getWorkspaceRoot());
    boolean agentRootAbsolute = absolute(agentWorkspaceRoot);
    boolean nonRoot = sandbox.getRuntimeUid() > 0 && sandbox.getRuntimeGid() > 0;
    if (!imagePinned) reasons.add("SANDBOX_IMAGE_NOT_DIGEST_PINNED");
    if (!sandboxRootAbsolute) reasons.add("SANDBOX_WORKSPACE_ROOT_NOT_ABSOLUTE");
    if (!agentRootAbsolute) reasons.add("AGENT_WORKSPACE_ROOT_NOT_ABSOLUTE");
    if (!nonRoot) reasons.add("SANDBOX_RUNTIME_IDENTITY_NOT_NON_ROOT");
    return new RuntimeEvidence(
        sandbox.getImage(),
        imagePinned,
        sandbox.getRuntimeUid(),
        sandbox.getRuntimeGid(),
        nonRoot,
        sandbox.getWorkspaceRoot(),
        sandboxRootAbsolute,
        agentWorkspaceRoot,
        agentRootAbsolute,
        sandbox.getCpus(),
        sandbox.getMemoryBytes(),
        sandbox.getPids(),
        sandbox.getTmpfsBytes(),
        sandbox.getWallClock().toSeconds(),
        imagePinned && sandboxRootAbsolute && agentRootAbsolute && nonRoot);
  }

  private static java.util.Optional<String> flagValue(List<FlagEvidence> flags, String key) {
    return flags.stream()
        .filter(flag -> key.equals(flag.key()))
        .map(FlagEvidence::effectiveValue)
        .findFirst();
  }

  private static boolean absolute(String value) {
    try {
      return value != null && !value.isBlank() && Path.of(value).isAbsolute();
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static String bundleDigest(
      ReleaseEvidence release,
      SchemaEvidence schema,
      List<FlagEvidence> flags,
      ProtocolEvidence protocol,
      RuntimeEvidence runtime,
      GraphEvidence graph,
      List<String> reasons) {
    StringBuilder canonical = new StringBuilder("CONTROL_PLANE_RELEASE_BUNDLE_V1");
    add(canonical, release);
    WorkspaceGenerationSchemaAttestor.Attestation attestation = schema.attestation();
    add(canonical, attestation.databaseReachable());
    add(canonical, attestation.latestSuccessfulMigration());
    add(canonical, attestation.requiredMigration());
    add(canonical, attestation.ready());
    add(canonical, attestation.reason());
    attestation.artifacts().stream()
        .sorted(
            Comparator.comparing(WorkspaceGenerationSchemaAttestor.Artifact::kind)
                .thenComparing(WorkspaceGenerationSchemaAttestor.Artifact::name))
        .forEach(value -> add(canonical, value));
    if (schema.exported() != null) {
      schema.exported().appliedMigrations().forEach(value -> add(canonical, value));
      schema.exported().definitions().forEach(value -> add(canonical, value));
    }
    flags.stream()
        .sorted(Comparator.comparing(FlagEvidence::key))
        .forEach(value -> add(canonical, value));
    add(canonical, protocol);
    add(canonical, runtime);
    add(canonical, graph);
    reasons.forEach(value -> add(canonical, value));
    return sha256(canonical.toString());
  }

  private static void add(StringBuilder target, Object value) {
    String text = String.valueOf(value);
    target.append('|').append(text.length()).append(':').append(text);
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static boolean validSha(String value) {
    return SHA256.matcher(normalizeLower(value)).matches();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static String normalizeLower(String value) {
    return normalize(value).toLowerCase(Locale.ROOT);
  }

  private record PersistedFlag(
      String key, String value, String valueType, Instant updatedAt, String updatedBy) {}

  public record ReleaseEvidence(
      String runningSha,
      String artifactSha256,
      String deploymentAttemptId,
      String authority,
      boolean complete) {}

  public record SchemaEvidence(
      WorkspaceGenerationSchemaAttestor.Attestation attestation,
      ControlPlaneSchemaEvidenceExporter.Evidence exported,
      boolean complete) {}

  public record FlagEvidence(
      String key,
      boolean persisted,
      String persistedValue,
      String effectiveValue,
      String valueType,
      Instant updatedAt,
      String updatedBy,
      boolean processParity) {}

  public record ProtocolEvidence(String admittedBuildProtocol, String llmOperationAdmissionMode) {}

  public record RuntimeEvidence(
      String sandboxImage,
      boolean imagePinnedByDigest,
      int runtimeUid,
      int runtimeGid,
      boolean nonRootIdentity,
      String sandboxWorkspaceRoot,
      boolean sandboxWorkspaceRootAbsolute,
      String agentWorkspaceRoot,
      boolean agentWorkspaceRootAbsolute,
      double cpus,
      long memoryBytes,
      int pids,
      long tmpfsBytes,
      long wallClockSeconds,
      boolean complete) {}

  public record GraphEvidence(
      String graphSha256,
      String graphManifestSha256,
      String graphifyVersion,
      String sqlParserVersion,
      String hclParserVersion,
      boolean complete) {}

  public record Attestation(
      int schemaVersion,
      Instant checkedAt,
      String authority,
      ReleaseEvidence release,
      SchemaEvidence schema,
      List<FlagEvidence> featureFlags,
      ProtocolEvidence protocol,
      RuntimeEvidence runtime,
      GraphEvidence graph,
      boolean evidenceComplete,
      boolean safeDarkState,
      List<String> reasons,
      String bundleSha256) {}
}
