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
const SLUG = /^[a-z0-9][a-z0-9._/-]*$/;
const REQUIRED_EQUAL_FIELDS = new Set([
  "runMode",
  "executionMode",
  "route.provider",
  "route.model",
  "case.specSha256",
  "case.acceptanceSha256",
  "runOptions",
]);
const REQUIRED_EVIDENCE = new Set([
  "releaseSha",
  "runId",
  "terminalStatus",
  "provider",
  "model",
  "costUsd",
  "acceptedWorkspaceGeneration",
  "latestWorkspaceGenerationAttemptState",
  "unresolvedWorkspaceGenerationAttemptCount",
  "workspaceArchiveSha256",
  "heldOutPassed",
  "heldOutTotal",
  "generatedCodeReview",
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

export function validatePlan(plan) {
  const errors = [];
  if (plan.schemaVersion !== 1) errors.push("schemaVersion must be 1");
  if (plan.status !== "PREPARED_NOT_EXECUTED") {
    errors.push("status must be PREPARED_NOT_EXECUTED until paid qualification evidence exists");
  }
  if (plan.runMode !== "BUILD") errors.push("runMode must be BUILD");
  if (plan.executionMode !== "PROTOTYPE") errors.push("executionMode must be PROTOTYPE");
  if (!["DISTINCT_HOSTS", "SEQUENTIAL_SAME_HOST"].includes(plan.executionTopology)) {
    errors.push("executionTopology must be DISTINCT_HOSTS or SEQUENTIAL_SAME_HOST");
  }

  const arms = Array.isArray(plan.arms) ? plan.arms : [];
  if (arms.length !== 2 || arms.map((arm) => arm.id).join(",") !== "control,candidate") {
    errors.push("arms must be exactly [control, candidate] in that order");
  }
  for (const arm of arms) {
    if (!/^[A-Z][A-Z0-9_]*$/.test(arm.baseUrlEnv || "")) {
      errors.push(`arm ${arm.id || "<unknown>"} baseUrlEnv must be an environment-variable name`);
    }
    if (!/^[A-Z][A-Z0-9_]*$/.test(arm.releaseShaEnv || "")) {
      errors.push(`arm ${arm.id || "<unknown>"} releaseShaEnv must be an environment-variable name`);
    }
  }

  const routes = Array.isArray(plan.routes) ? plan.routes : [];
  if (routes.length < 2) errors.push("at least two exact provider/model routes are required");
  const routeKeys = new Set();
  for (const route of routes) {
    if (!SLUG.test(route.provider || "")) errors.push("route.provider must be a lowercase slug");
    if (!SLUG.test(route.model || "")) errors.push("route.model must be a lowercase matrix id");
    const key = `${route.provider}/${route.model}`;
    if (routeKeys.has(key)) errors.push(`duplicate route ${key}`);
    routeKeys.add(key);
    if (!(Number.isFinite(route.safeMaxUsdPerRun) && route.safeMaxUsdPerRun > 0)) {
      errors.push(`${key} safeMaxUsdPerRun must be finite and positive`);
    }
  }
  const plannedSpend = 2 * routes.reduce(
    (sum, route) => sum + (Number.isFinite(route.safeMaxUsdPerRun) ? route.safeMaxUsdPerRun : 0),
    0,
  ) * (Array.isArray(plan.cases) ? plan.cases.length : 0);
  if (!(Number.isFinite(plan.safeMaxTotalUsd) && plan.safeMaxTotalUsd > 0)) {
    errors.push("safeMaxTotalUsd must be finite and positive");
  } else if (plannedSpend > plan.safeMaxTotalUsd) {
    errors.push(`planned maximum spend $${plannedSpend} exceeds safeMaxTotalUsd $${plan.safeMaxTotalUsd}`);
  }

  const cases = Array.isArray(plan.cases) ? plan.cases : [];
  if (cases.length === 0) errors.push("at least one BUILD qualification case is required");
  const caseIds = new Set();
  for (const testCase of cases) {
    if (!testCase.id || caseIds.has(testCase.id)) errors.push(`duplicate or missing case id ${testCase.id}`);
    caseIds.add(testCase.id);
    if (!new Set(["GREENFIELD", "BROWNFIELD"]).has(testCase.kind)) {
      errors.push(`${testCase.id} kind must be GREENFIELD or BROWNFIELD`);
    }
    const spec = confinedFile(testCase.specPath, `${testCase.id}.specPath`, errors);
    const acceptance = confinedFile(testCase.acceptancePath, `${testCase.id}.acceptancePath`, errors);
    if (!SHA256.test(testCase.specSha256 || "")) errors.push(`${testCase.id}.specSha256 is invalid`);
    if (!SHA256.test(testCase.acceptanceSha256 || "")) {
      errors.push(`${testCase.id}.acceptanceSha256 is invalid`);
    }
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

  const equalFields = new Set(plan.pairedEqualFields || []);
  for (const field of REQUIRED_EQUAL_FIELDS) {
    if (!equalFields.has(field)) errors.push(`pairedEqualFields is missing ${field}`);
  }
  const evidence = new Set(plan.requiredEvidence || []);
  for (const field of REQUIRED_EVIDENCE) {
    if (!evidence.has(field)) errors.push(`requiredEvidence is missing ${field}`);
  }
  if (plan.requiredRunOptions?.skipApproval !== true) errors.push("skipApproval must be true");
  if (plan.requiredRunOptions?.requireConfirmationBeforeExpensiveContinuation !== true) {
    errors.push("expensive continuations must require confirmation");
  }
  if (plan.qualificationRules?.terminalStatus !== "COMPLETE") {
    errors.push("qualification terminalStatus must be COMPLETE");
  }
  if (plan.qualificationRules?.unresolvedWorkspaceGenerationAttemptCount !== 0) {
    errors.push("qualification must require zero unresolved workspace-generation attempts");
  }
  for (const field of [
    "workspaceArchiveMustMatchAcceptedGeneration",
    "systemVerifiedScoreIsNonAuthoritative",
    "manualGeneratedCodeReviewRequired",
    "allHeldOutTestsMustPass",
  ]) {
    if (plan.qualificationRules?.[field] !== true) errors.push(`qualificationRules.${field} must be true`);
  }
  return errors;
}

export function validateAuthorizedEnvironment(plan, environment = process.env) {
  const errors = [];
  const values = new Map();
  for (const arm of plan.arms || []) {
    const url = environment[arm.baseUrlEnv];
    const sha = environment[arm.releaseShaEnv];
    if (!url) errors.push(`${arm.baseUrlEnv} is required for authorized execution`);
    else {
      try {
        const parsed = new URL(url);
        if (parsed.protocol !== "https:") errors.push(`${arm.baseUrlEnv} must use HTTPS`);
        values.set(`${arm.id}.url`, parsed.toString().replace(/\/$/, ""));
      } catch {
        errors.push(`${arm.baseUrlEnv} is not a valid URL`);
      }
    }
    if (!RELEASE_SHA.test(sha || "")) errors.push(`${arm.releaseShaEnv} must be a 40/64-char lowercase SHA`);
    else values.set(`${arm.id}.sha`, sha);
  }
  if (values.get("control.sha") && values.get("control.sha") === values.get("candidate.sha")) {
    errors.push("control and candidate release SHAs must differ");
  }
  if (plan.executionTopology === "DISTINCT_HOSTS"
      && values.get("control.url") && values.get("control.url") === values.get("candidate.url")) {
    errors.push("control and candidate URLs must differ for DISTINCT_HOSTS execution");
  }
  if (plan.executionTopology === "SEQUENTIAL_SAME_HOST"
      && values.get("control.url") && values.get("control.url") !== values.get("candidate.url")) {
    errors.push("control and candidate URLs must match for SEQUENTIAL_SAME_HOST execution");
  }
  return errors;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const manifestPath = path.resolve(process.argv[2] || defaultManifest);
  const plan = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const errors = validatePlan(plan);
  if (process.argv.includes("--authorized-preflight")) {
    errors.push(...validateAuthorizedEnvironment(plan));
  }
  if (errors.length) {
    process.stderr.write(`${errors.join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write(
    `Validated paired BUILD qualification plan: ${plan.cases.length} case(s) x ${plan.routes.length} route(s) x 2 arms\n`,
  );
}
