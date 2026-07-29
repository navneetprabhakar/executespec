package com.navneet.executespec.agent.requirement;

import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.observability.Metrics;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Additive RequirementIR persistence used before gate enforcement exists.
 *
 * <p>The service never mutates or repairs an existing artifact. It returns a typed, metered outcome
 * so the caller can preserve the legacy plan path during shadow rollout without making evidence
 * loss invisible.
 */
@Service
@Slf4j
public class RunRequirementContractShadowService {
  private final RunRequirementContractRepository repository;
  private final ObjectMapper objectMapper;
  private final RequirementIrCompiler compiler;
  private final RequirementIrCodec codec;
  private final Metrics metrics;

  public RunRequirementContractShadowService(
      RunRequirementContractRepository repository, ObjectMapper objectMapper, Metrics metrics) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.compiler = new RequirementIrCompiler();
    this.codec = new RequirementIrCodec(objectMapper);
    this.metrics = metrics;
  }

  /**
   * Persist an immutable artifact derived from the exact plan JSON that the legacy row will use.
   */
  public ShadowWriteResult persist(String runId, String exactSource, String exactPlanJson) {
    if (!hasText(runId) || exactSource == null || !hasText(exactPlanJson)) {
      return record(
          new ShadowWriteResult(ShadowWriteOutcome.COMPILE_FAILED, null, null, "INVALID_INPUT"));
    }
    try {
      Plan exactPlan = objectMapper.readValue(exactPlanJson, Plan.class);
      RequirementIrDocument document = compiler.compile(exactSource, exactPlan);
      RequirementIrValidator.ValidationResult structure =
          RequirementIrValidator.validateStructure(document, exactSource);
      if (!structure.valid()) {
        return record(
            new ShadowWriteResult(
                ShadowWriteOutcome.COMPILE_FAILED,
                null,
                null,
                "STRUCTURE_INVALID:" + stableViolationCodes(structure)));
      }

      String canonicalJson = codec.writeCanonical(document);
      String planFingerprint = codec.exactPlanFingerprint(exactPlanJson);
      String artifactDigest = codec.digest(document);
      String boundDigest = codec.boundDigest(document, exactPlanJson, exactSource);

      var existing =
          repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
              runId, planFingerprint, document.schemaVersion(), document.compilerFingerprint());
      if (existing.isPresent()) {
        if (matches(existing.get(), document, canonicalJson, artifactDigest, boundDigest)) {
          return record(
              new ShadowWriteResult(
                  ShadowWriteOutcome.IDEMPOTENT_REPLAY,
                  planFingerprint,
                  boundDigest,
                  "EXACT_REPLAY"));
        }
        return record(
            new ShadowWriteResult(
                ShadowWriteOutcome.CONFLICT,
                planFingerprint,
                boundDigest,
                "IMMUTABLE_IDENTITY_CONFLICT"));
      }

      long blockingIssues = document.issues().stream().filter(issue -> issue.blocking()).count();
      RunRequirementContract candidate =
          RunRequirementContract.builder()
              .runId(runId)
              .schemaVersion(document.schemaVersion())
              .compilerFingerprint(document.compilerFingerprint())
              .sourceSha256(document.sourceSha256())
              .exactPlanFingerprint(planFingerprint)
              .canonicalArtifactJson(canonicalJson)
              .artifactDigest(artifactDigest)
              .boundDigest(boundDigest)
              .completeness(document.completeness())
              .requirementCount(document.requirements().size())
              .acceptanceCriterionCount(document.acceptanceCriteria().size())
              .taskBindingCount(document.taskBindings().size())
              .issueCount(document.issues().size())
              .blockingIssueCount(Math.toIntExact(blockingIssues))
              .build();
      try {
        repository.saveAndFlush(candidate);
      } catch (DataIntegrityViolationException duplicateRace) {
        var winner =
            repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
                runId, planFingerprint, document.schemaVersion(), document.compilerFingerprint());
        if (winner.isPresent()
            && matches(winner.get(), document, canonicalJson, artifactDigest, boundDigest)) {
          return record(
              new ShadowWriteResult(
                  ShadowWriteOutcome.IDEMPOTENT_REPLAY,
                  planFingerprint,
                  boundDigest,
                  "CONCURRENT_EXACT_REPLAY"));
        }
        return record(
            new ShadowWriteResult(
                ShadowWriteOutcome.CONFLICT,
                planFingerprint,
                boundDigest,
                "CONCURRENT_IDENTITY_CONFLICT"));
      }
      return record(
          new ShadowWriteResult(
              ShadowWriteOutcome.PERSISTED, planFingerprint, boundDigest, "PERSISTED"));
    } catch (RequirementIrCodec.RequirementIrCodecException
        | com.fasterxml.jackson.core.JacksonException e) {
      log.warn(
          "run={} RequirementIR shadow compile failed errorClass={}",
          runId,
          e.getClass().getSimpleName());
      return record(
          new ShadowWriteResult(
              ShadowWriteOutcome.COMPILE_FAILED, null, null, e.getClass().getSimpleName()));
    } catch (Exception e) {
      log.warn(
          "run={} RequirementIR shadow persistence failed errorClass={}",
          runId,
          e.getClass().getSimpleName());
      return record(
          new ShadowWriteResult(
              ShadowWriteOutcome.PERSIST_FAILED, null, null, e.getClass().getSimpleName()));
    }
  }

  private ShadowWriteResult record(ShadowWriteResult result) {
    try {
      metrics.recordRequirementContractShadow(
          result.outcome().name().toLowerCase(java.util.Locale.ROOT));
    } catch (RuntimeException metricFailure) {
      log.warn(
          "RequirementIR shadow metric failed outcome={} errorClass={}",
          result.outcome(),
          metricFailure.getClass().getSimpleName());
    }
    return result;
  }

  private static boolean matches(
      RunRequirementContract existing,
      RequirementIrDocument document,
      String canonicalJson,
      String artifactDigest,
      String boundDigest) {
    return Objects.equals(existing.getSourceSha256(), document.sourceSha256())
        && Objects.equals(existing.getCanonicalArtifactJson(), canonicalJson)
        && Objects.equals(existing.getArtifactDigest(), artifactDigest)
        && Objects.equals(existing.getBoundDigest(), boundDigest)
        && existing.getCompleteness() == document.completeness()
        && existing.getRequirementCount() == document.requirements().size()
        && existing.getAcceptanceCriterionCount() == document.acceptanceCriteria().size()
        && existing.getTaskBindingCount() == document.taskBindings().size()
        && existing.getIssueCount() == document.issues().size()
        && existing.getBlockingIssueCount()
            == document.issues().stream().filter(issue -> issue.blocking()).count();
  }

  private static String stableViolationCodes(RequirementIrValidator.ValidationResult validation) {
    return validation.violations().stream()
        .map(violation -> violation.code().name())
        .distinct()
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public enum ShadowWriteOutcome {
    PERSISTED,
    IDEMPOTENT_REPLAY,
    CONFLICT,
    COMPILE_FAILED,
    PERSIST_FAILED
  }

  public record ShadowWriteResult(
      ShadowWriteOutcome outcome,
      String exactPlanFingerprint,
      String boundDigest,
      String reasonCode) {}
}
