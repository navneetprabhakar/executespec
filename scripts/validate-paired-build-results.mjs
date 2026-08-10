#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const SHA256 = /^[a-f0-9]{64}$/;
const RELEASE_SHA = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/;

function tuple(record) {
  return `${record.qualificationArm}/${String(record.requestedProvider).toLowerCase()}/${record.requestedModel}/${record.case}`;
}

function reviewIsBound(review, record, verdict) {
  return review && review.verdict === verdict && review.runId === record.runId
    && typeof review.notes === "string" && review.notes.trim().length >= 20;
}

export function validateResultRecords(plan, records, reviews = new Map(), options = {}) {
  const controlsOnly = options.controlsOnly === true;
  const errors = [];
  const expected = new Map();
  for (const arm of plan.arms) {
    if (controlsOnly && arm.id !== "control") continue;
    for (const route of plan.routes) for (const testCase of plan.cases) {
      expected.set(`${arm.id}/${route.provider}/${route.model}/${testCase.id}`, { arm, route, testCase });
    }
  }
  const seen = new Set();
  const releaseByArm = new Map();
  let freshCost = 0;
  let freshCount = 0;
  for (const record of records) {
    const key = tuple(record);
    const definition = expected.get(key);
    if (!definition) { errors.push(`unexpected result tuple ${key}`); continue; }
    if (seen.has(key)) errors.push(`duplicate result tuple ${key}`);
    seen.add(key);
    const { arm, route, testCase } = definition;
    if (!RELEASE_SHA.test(record.releaseSha || "")) errors.push(`${key} releaseSha is invalid`);
    else if (releaseByArm.has(arm.id) && releaseByArm.get(arm.id) !== record.releaseSha) {
      errors.push(`${key} releaseSha differs within one arm`);
    } else releaseByArm.set(arm.id, record.releaseSha);
    if (record.specSha256 !== testCase.specSha256) errors.push(`${key} spec digest differs from the frozen case`);
    if (String(record.provider).toLowerCase() !== route.provider || record.model !== route.model) {
      errors.push(`${key} effective route diverged from the paired exact route`);
    }
    if (!(Number.isFinite(record.costUsd) && record.costUsd >= 0 && record.costUsd <= route.safeMaxUsdPerRun)) {
      errors.push(`${key} cost exceeds the $${route.safeMaxUsdPerRun} per-run cap`);
    }
    if (record.unresolvedWorkspaceGenerationAttemptCount !== 0) {
      errors.push(`${key} has unresolved workspace-generation attempts`);
    }
    if (arm.source === "LIVE_CANDIDATE") {
      freshCount += 1;
      freshCost += Number(record.costUsd) || 0;
      validateAccepted(key, record, reviews.get(key), errors);
    } else {
      const expectedRunId = arm.runIdsByRoute[`${route.provider}/${route.model}`];
      if (record.runId !== expectedRunId) errors.push(`${key} is not the pinned historical control run`);
      validateRolledBack(key, record, reviews.get(key), errors);
    }
  }
  for (const key of expected.keys()) if (!seen.has(key)) errors.push(`missing result tuple ${key}`);
  if (!controlsOnly) {
    if (freshCount !== plan.freshRunCount) errors.push(`expected ${plan.freshRunCount} fresh candidate runs, found ${freshCount}`);
    if (freshCost > plan.safeMaxTotalUsd) errors.push(`fresh candidate cost $${freshCost} exceeds $${plan.safeMaxTotalUsd}`);
    if (releaseByArm.get("control") === releaseByArm.get("candidate")) {
      errors.push("historical control and candidate releases are identical");
    }
  }
  return errors;
}

function validateAccepted(key, record, review, errors) {
  if (record.terminalStatus !== "COMPLETE") errors.push(`${key} did not reach COMPLETE`);
  if (record.latestWorkspaceGenerationAttemptState !== "ACCEPTED") errors.push(`${key} latest attempt is not ACCEPTED`);
  if (record.sharedKernelFlagEnabledAtSubmission !== true) {
    errors.push(`${key} was not submitted with the shared BUILD kernel flag enabled`);
  }
  if (record.releaseIdentityAuthority !== "DEPLOYMENT_HOST:/opt/app/RUNNING_SHA") {
    errors.push(`${key} lacks deployment-host RUNNING_SHA authority`);
  }
  if (record.attestedRunningSha !== record.releaseSha || !/^[a-f0-9]{40}$/.test(record.attestedRunningSha || "")) {
    errors.push(`${key} attested running SHA does not match its candidate release`);
  }
  if (typeof record.releaseIdentityReadAt !== "string"
      || !/^\d{4}-\d{2}-\d{2}T.*Z$/.test(record.releaseIdentityReadAt)) {
    errors.push(`${key} lacks a valid release-identity read timestamp`);
  }
  if (record.initialFailureOutcome !== "FAILURE_OBSERVED"
      || typeof record.initialFailureCode !== "string" || !record.initialFailureCode.trim()) {
    errors.push(`${key} lacks the observed failure-first evidence required by the shared kernel`);
  }
  if (!Number.isInteger(record.acceptanceCheckCount) || record.acceptanceCheckCount < 1) {
    errors.push(`${key} lacks the frozen shared-kernel acceptance checks`);
  }
  for (const field of ["priorAcceptedWorkspaceGeneration", "acceptedWorkspaceGeneration", "workspaceArchiveSha256", "workspaceArchiveGeneration"]) {
    if (!SHA256.test(record[field] || "")) errors.push(`${key} ${field} is missing`);
  }
  if (record.workspaceArchiveGeneration !== record.acceptedWorkspaceGeneration) {
    errors.push(`${key} archive is not bound to the accepted generation`);
  }
  if (!(Number.isInteger(record.heldOutTotal) && record.heldOutTotal > 0
      && record.heldOutPassed === record.heldOutTotal)) {
    errors.push(`${key} did not pass every held-out test`);
  }
  if (!reviewIsBound(review, record, "PASS")
      || review.workspaceArchiveSha256 !== record.workspaceArchiveSha256
      || review.acceptedWorkspaceGeneration !== record.acceptedWorkspaceGeneration) {
    errors.push(`${key} lacks an accepted-generation-bound independent code review`);
  }
}

function validateRolledBack(key, record, review, errors) {
  if (record.latestWorkspaceGenerationAttemptState !== "ROLLED_BACK") errors.push(`${key} latest attempt is not ROLLED_BACK`);
  for (const field of ["priorAcceptedWorkspaceGeneration", "rejectedDiffSha256", "rollbackRestoredGeneration", "deterministicFailureEvidenceSha256"]) {
    if (!SHA256.test(record[field] || "")) errors.push(`${key} ${field} is missing`);
  }
  if (record.candidateEvidenceKind === "FROZEN_GENERATION") {
    if (!SHA256.test(record.candidateWorkspaceGeneration || "")) {
      errors.push(`${key} frozen candidate generation is missing`);
    } else if (record.candidateWorkspaceGeneration === record.priorAcceptedWorkspaceGeneration) {
      errors.push(`${key} negative control candidate equals the prior frontier`);
    }
  } else if (record.candidateEvidenceKind !== "LEGACY_REJECTED_DIFF_ONLY"
      || record.candidateWorkspaceGeneration != null) {
    errors.push(`${key} candidate evidence kind is invalid`);
  }
  if (record.rollbackRestoredGeneration !== record.priorAcceptedWorkspaceGeneration) {
    errors.push(`${key} rollback did not restore the exact prior frontier`);
  }
  if (!new Set(["IMMUTABLE_VERIFICATION_RECEIPT", "DURABLE_TASK_SMOKE"]).has(record.deterministicFailureSource)
      || record.deterministicTestsExecuted !== true
      || !Number.isInteger(record.deterministicTestsFailed)
      || record.deterministicTestsFailed < 1) {
    errors.push(`${key} lacks deterministic failed-verification evidence`);
  }
  if (!reviewIsBound(review, record, "REJECTION_CONFIRMED")
      || review.rejectedDiffSha256 !== record.rejectedDiffSha256
      || review.candidateEvidenceKind !== record.candidateEvidenceKind
      || (record.candidateEvidenceKind === "FROZEN_GENERATION"
        && review.candidateWorkspaceGeneration !== record.candidateWorkspaceGeneration)) {
    errors.push(`${key} lacks an independently reviewed rejected diff`);
  }
}

function filesUnder(root, name) {
  if (!fs.existsSync(root)) return [];
  return fs.readdirSync(root, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name === name)
    .map((entry) => path.join(entry.parentPath, entry.name));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const planPath = path.resolve(process.argv[2] || path.join(scriptDir, "build-qualification/paired-build-v1.json"));
  const resultRoot = path.resolve(process.argv[3] || path.join(scriptDir, "build-qualification/results/build-workspace-generation-v2"));
  const controlsOnly = process.argv.includes("--controls-only");
  const plan = JSON.parse(fs.readFileSync(planPath, "utf8"));
  const records = filesUnder(resultRoot, "completed-runs.jsonl").flatMap((file) =>
    fs.readFileSync(file, "utf8").split("\n").filter(Boolean).map((line) => JSON.parse(line)));
  const reviews = new Map();
  for (const file of filesUnder(path.join(resultRoot, "reviews"), "review.json")) {
    const review = JSON.parse(fs.readFileSync(file, "utf8"));
    reviews.set(review.tuple, review);
  }
  const errors = validateResultRecords(plan, records, reviews, { controlsOnly });
  if (errors.length) {
    process.stderr.write(`${errors.join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write(`BUILD qualification evidence passed ${records.length} reviewed record(s)\n`);
}
