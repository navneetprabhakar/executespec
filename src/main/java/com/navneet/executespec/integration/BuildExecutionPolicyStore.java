package com.navneet.executespec.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Immutable execution inputs shared by normal BUILD dispatch and crash recovery. */
@Service
@RequiredArgsConstructor
class BuildExecutionPolicyStore {
  private final PipelineRunRepository runs;
  private final ObjectMapper mapper;

  @Transactional
  public Snapshot freeze(
      String runId, String designBlock, boolean confirmation, boolean benchmark, String lowTier) {
    PipelineRun run = runs.findByIdForTaskContinuation(runId).orElseThrow();
    Snapshot proposed =
        new Snapshot(1, planDigest(run), designBlock, confirmation, benchmark, lowTier);
    if (run.getBuildExecutionPolicyJson() != null) {
      Snapshot stored = decode(run);
      if (stored.requireConfirmation() != confirmation
          || stored.requireBenchmark() != benchmark
          || !java.util.Objects.equals(stored.lowComplexityTier(), lowTier)) {
        throw new IllegalStateException("BUILD_EXECUTION_POLICY_CHANGED");
      }
      return stored;
    }
    try {
      run.setBuildExecutionPolicyJson(mapper.writeValueAsString(proposed));
      runs.saveAndFlush(run);
      return proposed;
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("BUILD_EXECUTION_POLICY_ENCODING_FAILED", invalid);
    }
  }

  Snapshot require(String runId) {
    return decode(runs.findById(runId).orElseThrow());
  }

  private Snapshot decode(PipelineRun run) {
    if (run.getBuildExecutionPolicyJson() == null) {
      throw new IllegalStateException(
          "BUILD_EXECUTION_POLICY_MISSING: recovery requires the original execution policy");
    }
    try {
      var json = mapper.readTree(run.getBuildExecutionPolicyJson());
      if (json.size() != 6
          || !json.path("requireConfirmation").isBoolean()
          || !json.path("requireBenchmark").isBoolean()) {
        throw new IllegalStateException("BUILD_EXECUTION_POLICY_MALFORMED");
      }
      Snapshot snapshot = mapper.treeToValue(json, Snapshot.class);
      if (snapshot.schemaVersion() != 1 || !planDigest(run).equals(snapshot.planDigest())) {
        throw new IllegalStateException("BUILD_EXECUTION_POLICY_PLAN_MISMATCH");
      }
      return snapshot;
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("BUILD_EXECUTION_POLICY_MALFORMED", invalid);
    }
  }

  private static String planDigest(PipelineRun run) {
    if (run.getPlanJson() == null || run.getPlanJson().isBlank()) {
      throw new IllegalStateException("BUILD_EXECUTION_POLICY_PLAN_MISSING");
    }
    return org.apache.commons.codec.digest.DigestUtils.sha256Hex(run.getPlanJson());
  }

  record Snapshot(
      int schemaVersion,
      String planDigest,
      String designBlock,
      boolean requireConfirmation,
      boolean requireBenchmark,
      String lowComplexityTier) {}
}
