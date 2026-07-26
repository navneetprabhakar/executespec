package com.navneet.executespec.integration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequirementGateBinding;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Conflict-safe inserts used inside the atomic plan-gate open transaction. */
@Component
@RequiredArgsConstructor
class PlanGateOpenStore {
  private final NamedParameterJdbcTemplate jdbc;

  int insertGate(
      String gateKey,
      String runId,
      String continuation,
      String continuationSha256,
      int continuationVersion,
      String reportDigest,
      RequirementGateBinding binding,
      Instant now) {
    Map<String, Object> p = new HashMap<>();
    p.put("gateKey", gateKey);
    p.put("runId", runId);
    p.put("continuation", continuation);
    p.put("continuationSha", continuationSha256);
    p.put("continuationVersion", continuationVersion);
    p.put("reportDigest", reportDigest);
    p.put("bindingMode", binding.mode().name());
    p.put("bindingStatus", persistedStatus(binding));
    p.put("bindingReason", bindingReason(binding));
    p.put("contractId", binding.contractId());
    p.put("schemaVersion", identity(binding) ? binding.schemaVersion() : null);
    p.put("compiler", identity(binding) ? binding.compilerFingerprint() : null);
    p.put("sourceSha", identity(binding) ? binding.sourceSha256() : null);
    p.put("planSha", identity(binding) ? binding.exactPlanFingerprint() : null);
    p.put("artifactSha", identity(binding) ? binding.artifactDigest() : null);
    p.put("boundSha", identity(binding) ? binding.boundDigest() : null);
    p.put("boundAt", identity(binding) ? Timestamp.from(now) : null);
    p.put("now", Timestamp.from(now));
    return jdbc.update(
        """
        INSERT INTO gate_record (
          gate_key, run_id, gate_type, status, comment, created_at,
          binding_mode, binding_status, requirement_contract_id, binding_schema_version,
          binding_compiler_fingerprint, binding_source_sha256, binding_exact_plan_fingerprint,
          binding_artifact_digest, binding_bound_digest, binding_bound_at, binding_reason,
          contract_gate_report_digest, continuation_schema_version, continuation_sha256
        ) VALUES (
          :gateKey, :runId, 'PLAN', 'PENDING', :continuation, :now,
          :bindingMode, :bindingStatus, :contractId, :schemaVersion,
          :compiler, :sourceSha, :planSha, :artifactSha, :boundSha, :boundAt, :bindingReason,
          :reportDigest, :continuationVersion, :continuationSha
        ) ON CONFLICT (gate_key) DO NOTHING
        """,
        p);
  }

  int insertWaitingOutbox(
      String runId,
      String gateKey,
      String continuation,
      String continuationSha256,
      int continuationVersion,
      Instant now) {
    return jdbc.update(
        """
        INSERT INTO plan_gate_resume_outbox (
          run_id, gate_key, payload, payload_sha256, handler_contract_version,
          status, attempt_count, max_attempts, next_retry_at
        ) VALUES (
          :runId, :gateKey, :payload, :payloadSha, :handlerVersion,
          'WAITING', 0, 8, :now
        ) ON CONFLICT (run_id) DO NOTHING
        """,
        Map.of(
            "runId", runId,
            "gateKey", gateKey,
            "payload", continuation,
            "payloadSha", continuationSha256,
            "handlerVersion", continuationVersion,
            "now", Timestamp.from(now)));
  }

  private static boolean identity(RequirementGateBinding binding) {
    return binding.contractId() != null;
  }

  static String persistedStatus(RequirementGateBinding binding) {
    return binding.status().name().equals("LEGACY") ? "UNBOUND" : binding.status().name();
  }

  static String bindingReason(RequirementGateBinding binding) {
    String reason = binding.reasonCode().name();
    if (binding.reasonDetails() != null && !binding.reasonDetails().isBlank()) {
      reason += ":" + binding.reasonDetails();
    }
    return reason.length() <= 512 ? reason : reason.substring(0, 512);
  }
}
