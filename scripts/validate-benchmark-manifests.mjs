#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const manifestDir = path.resolve(scriptDir, "../infra/benchmark/manifests");
const SHA256 = /^[a-f0-9]{64}$/;
const PROVIDER_SLUG = /^[a-z0-9][a-z0-9._:/-]*$/;
const VALID_STATUS = new Set([
  "REPRODUCIBLE",
  "EXPLORATORY",
  "EXPLORATORY_RETROSPECTIVE",
  "SUPERSEDED",
  "INVALID",
]);
const REPAIR_OUTCOME = /^(?:PASS|FAIL|ERROR|NOT_RUN|UNKNOWN)$/;
const NO_OP_CLASSIFICATION = /^(?:NO_OP|PATCH_APPLIED|PATCH_REJECTED|PATCH_FAILED)$/;
const REQUIRED_BUDGETS = [
  "passes",
  "tasks",
  "concurrency",
  "contextTokens",
  "maxOutputTokens",
  "iterationLimit",
  "wallClockLimitSeconds",
  "costLimitUsd",
];

export function validateManifest(manifest, file = "<manifest>") {
  const errors = [];
  const add = (message) => errors.push(`${file}: ${message}`);
  if (manifest.schemaVersion !== 1) add("schemaVersion must be 1");
  if (!VALID_STATUS.has(manifest.status)) {
    add(`status must be one of ${[...VALID_STATUS].join(", ")}`);
  }
  if (!manifest.suite) add("suite is required");
  if (!manifest.model?.provider || !manifest.model?.matrixId) add("provider and matrixId are required");
  if (manifest.model?.providerSlug !== null && manifest.model?.providerSlug !== undefined
      && !PROVIDER_SLUG.test(String(manifest.model.providerSlug))) {
    add("model.providerSlug must be a lowercase provider slug or null");
  }
  if (!manifest.results || !Number.isInteger(manifest.results.resolved) || !Number.isInteger(manifest.results.total)) {
    add("results.resolved and results.total must be integers");
  } else {
    if (manifest.results.resolved < 0 || manifest.results.resolved > manifest.results.total) {
      add("resolved must be between zero and total");
    }
    const expected = manifest.results.total === 0 ? 0 : manifest.results.resolved / manifest.results.total;
    if (!Number.isFinite(manifest.results.resolutionRate)) {
      add("results.resolutionRate must be a finite number");
    } else if (Math.abs(expected - manifest.results.resolutionRate) > 1e-12) {
      add(`resolutionRate ${manifest.results.resolutionRate} does not equal ${expected}`);
    }
  }
  if (manifest.dataset?.sha256 !== null && !SHA256.test(manifest.dataset?.sha256 || "")) {
    add("dataset.sha256 must be null or a lowercase SHA-256");
  }
  for (const [name, value] of [
    ["repository.sha", manifest.repository?.sha],
    ["prompt.sha256", manifest.prompt?.sha256],
    ["toolSchema.sha256", manifest.toolSchema?.sha256],
    ["harness.sha256", manifest.harness?.sha256],
  ]) {
    if (value !== null && value !== undefined && !SHA256.test(String(value))) {
      add(`${name} must be null or a lowercase SHA-256`);
    }
  }
  if ("worker.run-journal.enabled" in (manifest.featureFlags || {})) {
    add("worker.run-journal.enabled is not a real flag; use execution.run-journal.enabled");
  }
  for (const field of REQUIRED_BUDGETS) {
    if (!(field in (manifest.budgets || {}))) add(`budgets.${field} is required`);
  }
  if (manifest.status === "REPRODUCIBLE") {
    const identities = [
      ["repository.sha", manifest.repository?.sha],
      ["model.providerSlug", manifest.model?.providerSlug],
      ["prompt.sha256", manifest.prompt?.sha256],
      ["toolSchema.sha256", manifest.toolSchema?.sha256],
      ["harness.sha256", manifest.harness?.sha256],
      ["dataset.sha256", manifest.dataset?.sha256],
    ];
    for (const [name, value] of identities) {
      if (!value) add(`${name} cannot be null for REPRODUCIBLE status`);
    }
    for (const field of REQUIRED_BUDGETS) {
      if (manifest.budgets?.[field] === null) add(`budgets.${field} cannot be null for REPRODUCIBLE status`);
    }
  }
  if (manifest.repairEvidenceRequired === true && !Array.isArray(manifest.repairAttempts)) {
    add("repairAttempts is required when repairEvidenceRequired is true");
  }
  if (manifest.repairAttempts !== undefined) {
    for (const error of validateRepairAttempts(manifest.repairAttempts, file)) errors.push(error);
  }
  return errors;
}

/**
 * Validate the per-attempt evidence needed to compare repair quality across providers/models.
 * This is deliberately fail-closed: an attempt with missing provenance or an unmeasured outcome
 * cannot silently enter a quality cohort. `patchHash` is required even for NO_OP attempts (the
 * canonical hash of the empty patch is the caller's responsibility).
 */
export function validateRepairAttempts(attempts, file = "<manifest>") {
  const errors = [];
  const add = (index, message) => errors.push(`${file}: repairAttempts[${index}] ${message}`);
  if (!Array.isArray(attempts)) {
    errors.push(`${file}: repairAttempts must be an array`);
    return errors;
  }
  attempts.forEach((attempt, index) => {
    if (!attempt || typeof attempt !== "object" || Array.isArray(attempt)) {
      add(index, "must be an object");
      return;
    }
    if (typeof attempt.assertion !== "string" || !attempt.assertion.trim()) {
      add(index, "assertion must be a non-empty string");
    }
    if (typeof attempt.patchHash !== "string" || !SHA256.test(attempt.patchHash)) {
      add(index, "patchHash must be a lowercase SHA-256");
    }
    for (const field of ["testOutcome", "buildOutcome", "reviewerOutcome"]) {
      if (typeof attempt[field] !== "string" || !REPAIR_OUTCOME.test(attempt[field])) {
        add(index, `${field} must be one of PASS, FAIL, ERROR, NOT_RUN, UNKNOWN`);
      }
    }
    if (typeof attempt.noOpClassification !== "string" || !NO_OP_CLASSIFICATION.test(attempt.noOpClassification)) {
      add(index, "noOpClassification must be NO_OP, PATCH_APPLIED, PATCH_REJECTED, or PATCH_FAILED");
    }
    if (typeof attempt.costUsd !== "number" || !Number.isFinite(attempt.costUsd) || attempt.costUsd < 0) {
      add(index, "costUsd must be a finite number >= 0");
    }
    if (typeof attempt.latencyMs !== "number" || !Number.isFinite(attempt.latencyMs) || attempt.latencyMs <= 0) {
      add(index, "latencyMs must be a finite number > 0");
    }
    if (typeof attempt.provider !== "string" || !PROVIDER_SLUG.test(attempt.provider)) {
      add(index, "provider must be a lowercase provider slug");
    }
    if (typeof attempt.model !== "string" || !attempt.model.trim()) {
      add(index, "model must be a non-empty string");
    }
  });
  return errors;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const files = fs
    .readdirSync(manifestDir)
    .filter((file) => file.endsWith(".json") && !file.endsWith(".schema.json"))
    .sort();
  const errors = files.flatMap((file) => {
    const manifest = JSON.parse(fs.readFileSync(path.join(manifestDir, file), "utf8"));
    return validateManifest(manifest, file);
  });
  if (errors.length > 0) {
    process.stderr.write(`${errors.join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write(`Validated ${files.length} benchmark manifest(s)\n`);
}
