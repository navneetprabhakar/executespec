#!/usr/bin/env node

/**
 * Offline provider/model quality-cohort adjudicator.
 *
 * This command consumes exported artifacts only. It never authenticates, calls an
 * LLM provider, creates a run, or treats a terminal status as proof of quality.
 * A record is scoreable only when an independently-authored held-out evaluator
 * passed, its output artifact exists with a matching SHA-256, and the command
 * exit status is zero. The validator deliberately fails closed when a cohort is
 * smaller than the configured minimum or when providers are not evaluated on
 * the same task set and fixture/evaluator fingerprints.
 *
 * Schema (v1):
 * {
 *   schemaVersion: 1,
 *   cohortId: "...",
 *   source: { fixtureSha256, evaluatorSha256, harnessSha256 },
 *   policy: { minTasksPerProvider: 3, providerBudgetsUsd: {anthropic: 10} },
 *   providers: [{ provider, model, tasks: [{ taskId, outcome, artifact,
 *     heldOut: {passed,total}, commandExitCode, spend: {actualUsd,
 *     providerReportedUsd, reconciled, cumulativeUsd} }]}]
 * }
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const EPSILON = 1e-6;

function text(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function error(errors, message, provider = null, taskId = null) {
  errors.push({ provider, taskId, message });
}

function finite(value, label, errors, provider, taskId) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    error(errors, `${label} must be a finite non-negative number`, provider, taskId);
    return false;
  }
  return true;
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function validateArtifact(artifact, baseDir, errors, provider, taskId) {
  if (!artifact || typeof artifact !== "object") {
    error(errors, "artifact evidence is required", provider, taskId);
    return false;
  }
  const relative = text(artifact.path);
  const expected = text(artifact.sha256)?.toLowerCase();
  if (!relative) error(errors, "artifact.path is required", provider, taskId);
  if (!expected || !/^[a-f0-9]{64}$/.test(expected)) error(errors, "artifact.sha256 must be a 64-character hex digest", provider, taskId);
  if (!relative || !expected) return false;
  const resolved = path.resolve(baseDir, relative);
  const root = path.resolve(baseDir);
  if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) {
    error(errors, "artifact.path escapes the cohort artifact directory", provider, taskId);
    return false;
  }
  if (!fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) {
    error(errors, "artifact file is missing", provider, taskId);
    return false;
  }
  const actual = sha256(resolved);
  if (actual !== expected) error(errors, "artifact SHA-256 does not match exported evidence", provider, taskId);
  return actual === expected;
}

function validateTask(task, provider, baseDir, errors) {
  const taskId = text(task?.taskId);
  if (!taskId) {
    error(errors, "taskId is required", provider, taskId);
    return null;
  }
  const heldOut = task.heldOut;
  const passed = heldOut?.passed;
  const total = heldOut?.total;
  if (!Number.isInteger(passed) || passed < 0) error(errors, "heldOut.passed must be a non-negative integer", provider, taskId);
  if (!Number.isInteger(total) || total <= 0) error(errors, "heldOut.total must be a positive integer", provider, taskId);
  if (Number.isInteger(passed) && Number.isInteger(total) && passed > total) error(errors, "heldOut.passed cannot exceed heldOut.total", provider, taskId);
  if (task.commandExitCode !== 0) error(errors, "independent evaluator command must exit with code 0", provider, taskId);
  if (task.outcome !== "COMPLETED") error(errors, "task outcome must be COMPLETED before it is scoreable", provider, taskId);
  const validArtifact = validateArtifact(task.artifact, baseDir, errors, provider, taskId);
  const spend = task.spend;
  if (!spend || typeof spend !== "object") {
    error(errors, "spend reconciliation is required even for zero-spend offline adjudication", provider, taskId);
  } else {
    finite(spend.actualUsd, "spend.actualUsd", errors, provider, taskId);
    finite(spend.providerReportedUsd, "spend.providerReportedUsd", errors, provider, taskId);
    finite(spend.cumulativeUsd, "spend.cumulativeUsd", errors, provider, taskId);
    if (spend.reconciled !== true) error(errors, "spend.reconciled must be true", provider, taskId);
    if (typeof spend.actualUsd === "number" && typeof spend.providerReportedUsd === "number" && Math.abs(spend.actualUsd - spend.providerReportedUsd) > 0.01) {
      error(errors, "provider-reported spend does not reconcile", provider, taskId);
    }
  }
  return {
    taskId,
    passed: Number.isInteger(passed) ? passed : 0,
    total: Number.isInteger(total) ? total : 0,
    validArtifact,
    actualUsd: typeof spend?.actualUsd === "number" ? spend.actualUsd : 0,
  };
}

export function validateProviderQualityCohort(input, baseDir = process.cwd()) {
  const errors = [];
  if (!input || typeof input !== "object") return { schemaVersion: 1, status: "FAIL", valid: false, errors: [{ message: "input must be an object" }] };
  if (input.schemaVersion !== 1) error(errors, "schemaVersion must equal 1");
  if (!text(input.cohortId)) error(errors, "cohortId is required");
  const source = input.source;
  for (const field of ["fixtureSha256", "evaluatorSha256", "harnessSha256"]) {
    if (!text(source?.[field]) || !/^[a-f0-9]{64}$/i.test(source[field])) error(errors, `source.${field} must be a 64-character hex digest`);
  }
  const policy = input.policy;
  const minTasks = policy?.minTasksPerProvider;
  if (!Number.isInteger(minTasks) || minTasks < 3) error(errors, "policy.minTasksPerProvider must be an integer >= 3");
  const budgets = policy?.providerBudgetsUsd;
  if (!budgets || typeof budgets !== "object" || Array.isArray(budgets)) error(errors, "policy.providerBudgetsUsd is required");
  for (const [provider, budget] of Object.entries(budgets ?? {})) finite(budget, `policy.providerBudgetsUsd.${provider}`, errors, provider, null);
  const providers = input.providers;
  if (!Array.isArray(providers) || providers.length < 2) error(errors, "at least two providers are required for a comparison cohort");
  const byProvider = {};
  const taskSets = [];
  for (const entry of providers ?? []) {
    const provider = text(entry?.provider)?.toLowerCase();
    const model = text(entry?.model);
    if (!provider || !model) { error(errors, "provider and model are required"); continue; }
    if (byProvider[provider]) error(errors, "provider appears more than once", provider, null);
    const tasks = Array.isArray(entry.tasks) ? entry.tasks : [];
    if (tasks.length < (Number.isInteger(minTasks) ? minTasks : 3)) error(errors, `provider has fewer than ${Number.isInteger(minTasks) ? minTasks : 3} deterministic tasks`, provider, null);
    const seen = new Set();
    const results = [];
    for (const task of tasks) {
      const result = validateTask(task, provider, baseDir, errors);
      if (result) {
        if (seen.has(result.taskId)) error(errors, "duplicate taskId within provider", provider, result.taskId);
        seen.add(result.taskId);
        results.push(result);
      }
    }
    byProvider[provider] = { provider, model, tasks: results };
    taskSets.push({ provider, ids: [...seen].sort() });
    const budget = budgets?.[provider];
    if (budget === undefined) error(errors, `provider budget missing for ${provider}`, provider, null);
    const cumulative = results.reduce((sum, task) => sum + task.actualUsd, 0);
    if (budget !== undefined && cumulative > budget + EPSILON) error(errors, "provider spend exceeds configured budget", provider, null);
  }
  if (taskSets.length > 1) {
    const baseline = JSON.stringify(taskSets[0].ids);
    for (const set of taskSets.slice(1)) if (JSON.stringify(set.ids) !== baseline) error(errors, "providers must use the same deterministic task-id set", set.provider, null);
  }
  const providerSummaries = Object.values(byProvider).map(({ provider, model, tasks }) => ({
    provider, model, tasks: tasks.length, heldOutPassed: tasks.reduce((n, t) => n + t.passed, 0), heldOutTotal: tasks.reduce((n, t) => n + t.total, 0),
  }));
  return { schemaVersion: 1, cohortId: text(input.cohortId), status: errors.length ? "FAIL" : "PASS", valid: errors.length === 0, errors, providers: providerSummaries, policy: { minTasksPerProvider: minTasks, artifactLevel: true, independentHeldOutEvaluator: true } };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const inputPath = process.argv[2];
  const outputPath = process.argv[3];
  if (!inputPath) { console.error("usage: node scripts/provider-quality-cohort.mjs INPUT.json [REPORT.json]"); process.exit(2); }
  let input;
  try { input = JSON.parse(fs.readFileSync(path.resolve(inputPath), "utf8")); }
  catch (e) { console.error(`unable to read cohort: ${e.message}`); process.exit(2); }
  const report = validateProviderQualityCohort(input, path.dirname(path.resolve(inputPath)));
  const rendered = `${JSON.stringify(report, null, 2)}\n`;
  if (outputPath) fs.writeFileSync(path.resolve(outputPath), rendered);
  process.stdout.write(rendered);
  process.exit(report.valid ? 0 : 1);
}
