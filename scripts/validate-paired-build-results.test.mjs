import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { validateResultRecords } from "./validate-paired-build-results.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const plan = JSON.parse(fs.readFileSync(path.join(root, "scripts/build-qualification/paired-build-v1.json"), "utf8"));

function completeEvidence() {
  const records = [];
  const reviews = new Map();
  for (const [armIndex, arm] of plan.arms.entries()) for (const route of plan.routes) {
    const testCase = plan.cases[0];
    const key = `${arm.id}/${route.provider}/${route.model}/${testCase.id}`;
    const common = {
      qualificationArm: arm.id,
      releaseSha: armIndex ? "b".repeat(40) : arm.releaseSha,
      case: testCase.id,
      specSha256: testCase.specSha256,
      runId: arm.source === "LIVE_CANDIDATE" ? `${arm.id}-${route.provider}` : arm.runIdsByRoute[`${route.provider}/${route.model}`],
      requestedProvider: route.provider,
      requestedModel: route.model,
      provider: route.provider.toUpperCase(),
      model: route.model,
      costUsd: 0.5,
      unresolvedWorkspaceGenerationAttemptCount: 0,
      priorAcceptedWorkspaceGeneration: "a".repeat(64),
    };
    if (arm.expectedOutcome === "ACCEPTED") {
      const record = {
        ...common, terminalStatus: "COMPLETE", acceptedWorkspaceGeneration: "c".repeat(64),
        latestWorkspaceGenerationAttemptState: "ACCEPTED", workspaceArchiveSha256: "d".repeat(64),
        workspaceArchiveGeneration: "c".repeat(64), heldOutPassed: 5, heldOutTotal: 5,
        initialFailureOutcome: "FAILURE_OBSERVED", initialFailureCode: "REQUIRED_PATH_MISSING",
        acceptanceCheckCount: 4, sharedKernelFlagEnabledAtSubmission: true,
        releaseIdentityAuthority: "PROCESS_STARTUP_ENVIRONMENT",
        attestedRunningSha: common.releaseSha,
        attestedArtifactSha256: "9".repeat(64),
        attestedDeploymentAttemptId: "31497831910-1",
        releaseIdentityReadAt: "2026-08-11T00:00:00.123456789Z",
      };
      records.push(record);
      reviews.set(key, { verdict: "PASS", runId: record.runId, workspaceArchiveSha256: record.workspaceArchiveSha256,
        acceptedWorkspaceGeneration: record.acceptedWorkspaceGeneration,
        notes: "Independent review found complete idiomatic generated code." });
    } else {
      const record = {
        ...common, terminalStatus: "PARTIAL", candidateEvidenceKind: "LEGACY_REJECTED_DIFF_ONLY",
        candidateWorkspaceGeneration: null,
        rejectedDiffSha256: "e".repeat(64), rollbackRestoredGeneration: "a".repeat(64),
        latestWorkspaceGenerationAttemptState: "ROLLED_BACK",
        deterministicFailureSource: "DURABLE_TASK_SMOKE", deterministicTestsExecuted: true,
        deterministicTestsFailed: 1, deterministicFailureEvidenceSha256: "f".repeat(64),
      };
      records.push(record);
      reviews.set(key, { verdict: "REJECTION_CONFIRMED", runId: record.runId,
        rejectedDiffSha256: record.rejectedDiffSha256,
        candidateEvidenceKind: record.candidateEvidenceKind,
        notes: "Independent review confirms the speculative diff failed its deterministic contract." });
    }
  }
  return { records, reviews };
}

test("paired evidence proves two historical rollbacks and two accepted candidates", () => {
  const { records, reviews } = completeEvidence();
  assert.deepEqual(validateResultRecords(plan, records, reviews), []);
});

test("candidate evidence fails on route drift, cap breach, archive drift, and missing review", () => {
  const { records, reviews } = completeEvidence();
  const candidate = records.find((record) => record.qualificationArm === "candidate");
  candidate.provider = "OPENAI";
  candidate.costUsd = 5.01;
  candidate.workspaceArchiveGeneration = "f".repeat(64);
  reviews.clear();
  const errors = validateResultRecords(plan, records, reviews);
  assert.ok(errors.some((error) => error.includes("effective route diverged")));
  assert.ok(errors.some((error) => error.includes("per-run cap")));
  assert.ok(errors.some((error) => error.includes("archive is not bound")));
  assert.ok(errors.some((error) => error.includes("independent")));
});

test("candidate evidence fails when the run did not prove shared-kernel admission", () => {
  const { records, reviews } = completeEvidence();
  const candidate = records.find((record) => record.qualificationArm === "candidate");
  candidate.sharedKernelFlagEnabledAtSubmission = false;
  candidate.initialFailureOutcome = "";
  candidate.acceptanceCheckCount = 0;
  candidate.releaseIdentityAuthority = "CALLER_SUPPLIED";
  candidate.attestedRunningSha = "f".repeat(40);
  candidate.attestedArtifactSha256 = "bad";
  candidate.attestedDeploymentAttemptId = "bad attempt";
  candidate.releaseIdentityReadAt = "";
  const errors = validateResultRecords(plan, records, reviews);
  assert.ok(errors.some((error) => error.includes("shared BUILD kernel flag")));
  assert.ok(errors.some((error) => error.includes("failure-first evidence")));
  assert.ok(errors.some((error) => error.includes("acceptance checks")));
  assert.ok(errors.some((error) => error.includes("serving-process startup authority")));
  assert.ok(errors.some((error) => error.includes("attested running SHA")));
  assert.ok(errors.some((error) => error.includes("artifact SHA-256")));
  assert.ok(errors.some((error) => error.includes("deployment attempt")));
  assert.ok(errors.some((error) => error.includes("read timestamp")));
});

test("negative evidence fails unless exact prior frontier is restored and rejected diff reviewed", () => {
  const { records, reviews } = completeEvidence();
  const control = records.find((record) => record.qualificationArm === "control");
  control.rollbackRestoredGeneration = "f".repeat(64);
  control.deterministicTestsFailed = 0;
  reviews.delete(tuple(control));
  const errors = validateResultRecords(plan, records, reviews);
  assert.ok(errors.some((error) => error.includes("exact prior frontier")));
  assert.ok(errors.some((error) => error.includes("failed-verification")));
  assert.ok(errors.some((error) => error.includes("rejected diff")));
});

test("controls-only preflight accepts no candidate records and pins historical run ids", () => {
  const { records, reviews } = completeEvidence();
  const controls = records.filter((record) => record.qualificationArm === "control");
  assert.deepEqual(validateResultRecords(plan, controls, reviews, { controlsOnly: true }), []);
  controls[0].runId = "wrong";
  assert.ok(validateResultRecords(plan, controls, reviews, { controlsOnly: true })
    .some((error) => error.includes("pinned historical")));
});

function tuple(record) {
  return `${record.qualificationArm}/${record.requestedProvider}/${record.requestedModel}/${record.case}`;
}
