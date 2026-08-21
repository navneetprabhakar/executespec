#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, "..");
const defaultManifest = path.join(scriptDir, "build-qualification", "paired-build-v1.json");
const SHA256 = /^[a-f0-9]{64}$/;
const RELEASE_SHA = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/;
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const SLUG = /^[a-z0-9][a-z0-9._/-]*$/;
const REQUIRED_EQUAL_FIELDS = new Set([
  "runMode", "executionMode", "route.provider", "route.model",
  "case.specSha256", "case.acceptanceSha256", "runOptions",
]);
const REQUIRED_COMMON = new Set([
  "releaseSha", "runId", "provider", "model", "costUsd", "specSha256",
  "unresolvedWorkspaceGenerationAttemptCount",
]);
const REQUIRED_ACCEPTED = new Set([
  "terminalStatus", "priorAcceptedWorkspaceGeneration", "acceptedWorkspaceGeneration",
  "latestWorkspaceGenerationAttemptState", "workspaceArchiveSha256",
  "workspaceArchiveGeneration", "heldOutPassed", "heldOutTotal", "generatedCodeReview",
  "initialFailureOutcome", "initialFailureCode", "acceptanceCheckCount",
  "sharedKernelFlagEnabledAtSubmission",
  "releaseIdentityAuthority",
  "attestedRunningSha", "attestedArtifactSha256", "attestedDeploymentAttemptId",
  "releaseIdentityReadAt",
]);
const REQUIRED_ROLLED_BACK = new Set([
  "priorAcceptedWorkspaceGeneration", "candidateEvidenceKind", "rejectedDiffSha256",
  "rollbackRestoredGeneration", "latestWorkspaceGenerationAttemptState",
  "deterministicFailureSource", "deterministicTestsExecuted", "deterministicTestsFailed",
  "deterministicFailureEvidenceSha256", "generatedCodeReview",
]);

function digest(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function confinedFile(relative, label, errors) {
  if (typeof relative !== "string" || !relative) {
    errors.push(`${label} is required`);
    return null;
  }
  const absolute = path.resolve(root, relative);
  if (absolute !== root && !absolute.startsWith(`${root}${path.sep}`)) {
    errors.push(`${label} escapes the repository root`);
    return null;
  }
  if (!fs.statSync(absolute, { throwIfNoEntry: false })?.isFile()) {
    errors.push(`${label} does not name a file: ${relative}`);
    return null;
  }
  return absolute;
}

function requireFields(actual, required, label, errors) {
  const fields = new Set(actual || []);
  for (const field of required) if (!fields.has(field)) errors.push(`${label} is missing ${field}`);
}

export function validatePlan(plan) {
  const errors = [];
  if (plan.schemaVersion !== 2) errors.push("schemaVersion must be 2");
  if (plan.status !== "PREPARED_NOT_EXECUTED") {
    errors.push("status must be PREPARED_NOT_EXECUTED until paid qualification evidence exists");
  }
  if (plan.runMode !== "BUILD") errors.push("runMode must be BUILD");
  if (plan.executionMode !== "PROTOTYPE") errors.push("executionMode must be PROTOTYPE");
  if (plan.executionTopology !== "SEQUENTIAL_SAME_HOST") {
    errors.push("executionTopology must be SEQUENTIAL_SAME_HOST for the dev deployment");
  }

  const arms = Array.isArray(plan.arms) ? plan.arms : [];
  if (arms.length !== 2 || arms.map((arm) => arm.id).join(",") !== "control,candidate") {
    errors.push("arms must be exactly [control, candidate] in that order");
  }
  const control = arms.find((arm) => arm.id === "control");
  const candidate = arms.find((arm) => arm.id === "candidate");
  if (control?.source !== "HISTORICAL_IMMUTABLE_EVIDENCE" || control?.expectedOutcome !== "ROLLED_BACK") {
    errors.push("control must be historical immutable ROLLED_BACK evidence");
  }
  if (!RELEASE_SHA.test(control?.releaseSha || "")) {
    errors.push("control.releaseSha must pin the historical deployed release");
  }
  if (candidate?.source !== "LIVE_CANDIDATE" || candidate?.expectedOutcome !== "ACCEPTED") {
    errors.push("candidate must be the only live ACCEPTED arm");
  }
  for (const field of ["baseUrlEnv", "releaseShaEnv"]) {
    if (!/^[A-Z][A-Z0-9_]*$/.test(candidate?.[field] || "")) {
      errors.push(`candidate ${field} must be an environment-variable name`);
    }
  }

  const routes = Array.isArray(plan.routes) ? plan.routes : [];
  if (routes.length !== 2) errors.push("exactly two approved provider/model routes are required");
  const routeKeys = new Set();
  for (const route of routes) {
    if (!SLUG.test(route.provider || "")) errors.push("route.provider must be a lowercase slug");
    if (!SLUG.test(route.model || "")) errors.push("route.model must be a lowercase matrix id");
    const key = `${route.provider}/${route.model}`;
    if (routeKeys.has(key)) errors.push(`duplicate route ${key}`);
    routeKeys.add(key);
    if (route.safeMaxUsdPerRun !== 5) errors.push(`${key} safeMaxUsdPerRun must be exactly $5`);
    if (!UUID.test(control?.runIdsByRoute?.[key] || "")) {
      errors.push(`control historical run id missing for ${key}`);
    }
  }
  const cases = Array.isArray(plan.cases) ? plan.cases : [];
  const freshRunCount = routes.length * cases.length;
  const plannedSpend = routes.reduce((sum, route) => sum + Number(route.safeMaxUsdPerRun || 0), 0)
    * cases.length;
  if (plan.freshRunCount !== 2 || freshRunCount !== 2) errors.push("qualification must create exactly two fresh runs");
  if (plan.safeMaxTotalUsd !== 10 || plannedSpend !== 10) {
    errors.push("fresh qualification spend must be capped at exactly $10 total");
  }

  if (cases.length !== 1) errors.push("exactly one paired BUILD case is required");
  for (const testCase of cases) {
    if (!new Set(["GREENFIELD", "BROWNFIELD"]).has(testCase.kind)) {
      errors.push(`${testCase.id} kind must be GREENFIELD or BROWNFIELD`);
    }
    const spec = confinedFile(testCase.specPath, `${testCase.id}.specPath`, errors);
    const acceptance = confinedFile(testCase.acceptancePath, `${testCase.id}.acceptancePath`, errors);
    if (!SHA256.test(testCase.specSha256 || "")) errors.push(`${testCase.id}.specSha256 is invalid`);
    if (!SHA256.test(testCase.acceptanceSha256 || "")) errors.push(`${testCase.id}.acceptanceSha256 is invalid`);
    if (spec && SHA256.test(testCase.specSha256 || "") && digest(spec) !== testCase.specSha256) {
      errors.push(`${testCase.id} spec digest drifted`);
    }
    if (acceptance && SHA256.test(testCase.acceptanceSha256 || "")
        && digest(acceptance) !== testCase.acceptanceSha256) {
      errors.push(`${testCase.id} acceptance digest drifted`);
    }
  }
  const harness = confinedFile(plan.harness?.path, "harness.path", errors);
  if (!SHA256.test(plan.harness?.sha256 || "")) errors.push("harness.sha256 is invalid");
  if (harness && SHA256.test(plan.harness?.sha256 || "") && digest(harness) !== plan.harness.sha256) {
    errors.push("harness digest drifted");
  }

  requireFields(plan.pairedEqualFields, REQUIRED_EQUAL_FIELDS, "pairedEqualFields", errors);
  requireFields(plan.requiredCommonEvidence, REQUIRED_COMMON, "requiredCommonEvidence", errors);
  requireFields(plan.requiredAcceptedEvidence, REQUIRED_ACCEPTED, "requiredAcceptedEvidence", errors);
  requireFields(plan.requiredRolledBackEvidence, REQUIRED_ROLLED_BACK, "requiredRolledBackEvidence", errors);
  if (plan.requiredRunOptions?.skipApproval !== true) errors.push("skipApproval must be true");
  if (plan.requiredRunOptions?.requireConfirmationBeforeExpensiveContinuation !== true) {
    errors.push("expensive continuations must require confirmation");
  }
  const rules = plan.qualificationRules || {};
  for (const field of [
    "freshRunsAreCandidateOnly", "workspaceArchiveMustMatchAcceptedGeneration",
    "rollbackMustRestorePriorFrontier", "systemVerifiedScoreIsNonAuthoritative",
    "manualGeneratedCodeReviewRequired", "allHeldOutTestsMustPassOnAcceptedArm",
    "legacyRejectedDiffMaySubstituteForUnfrozenCandidateGeneration",
    "sharedKernelFlagMustBeEnabledAtSubmission",
  ]) if (rules[field] !== true) errors.push(`qualificationRules.${field} must be true`);
  if (rules.freshRunCount !== 2 || rules.safeMaxUsdPerRun !== 5) {
    errors.push("qualificationRules must pin two fresh runs at $5 each");
  }
  if (rules.unresolvedWorkspaceGenerationAttemptCount !== 0) {
    errors.push("qualification must require zero unresolved workspace-generation attempts");
  }
  return errors;
}

export function validateAuthorizedEnvironment(plan, environment = process.env) {
  const errors = [];
  const candidate = (plan.arms || []).find((arm) => arm.source === "LIVE_CANDIDATE");
  const url = environment[candidate?.baseUrlEnv];
  const sha = environment[candidate?.releaseShaEnv];
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:" || parsed.hostname !== "app.executespec.ai") {
      errors.push(`${candidate.baseUrlEnv} must be https://app.executespec.ai`);
    }
  } catch {
    errors.push(`${candidate?.baseUrlEnv || "candidate URL"} is not a valid URL`);
  }
  if (!/^[a-f0-9]{40}$/.test(sha || "")) {
    errors.push(`${candidate?.releaseShaEnv || "candidate SHA"} must be an exact 40-char lowercase commit SHA`);
  }
  return errors;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const manifestPath = path.resolve(process.argv[2] || defaultManifest);
  const plan = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const errors = validatePlan(plan);
  if (process.argv.includes("--authorized-preflight")) errors.push(...validateAuthorizedEnvironment(plan));
  if (errors.length) {
    process.stderr.write(`${errors.join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write(`Validated BUILD qualification: 2 historical controls + 2 capped candidate runs\n`);
}
