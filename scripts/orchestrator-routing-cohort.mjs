#!/usr/bin/env node

/**
 * Deterministic, offline routing-cohort validator.
 *
 * This is intentionally separate from orchestrator-routing-eval.sh: it consumes an exported,
 * already-recorded task-outcome fixture and never authenticates, calls a provider, or mutates a
 * run. Missing evidence is a failure, not an inferred pass. The input contract is schemaVersion 1:
 *
 * {
 *   "schemaVersion": 1,
 *   "providerBudgetsUsd": {"anthropic": 10},
 *   "records": [{
 *     "runId", "taskId", "category", "complexity", "provider", "model",
 *     "validationPassed": true,
 *     "route": {
 *       "currentProvider", "currentModel", "selectedProvider", "selectedModel",
 *       "changed", "reasonCode", "providerAvailable", "qualityEvidenceAvailable",
 *       "qualityScore", "qualitySampleSize",
 *       "qualityScope": {"category", "complexity"}
 *     },
 *     "spend": {
 *       "reservedUsd", "actualUsd", "providerReportedUsd", "reconciled",
 *       "cumulativeUsd", "remainingBudgetUsd"
 *     }
 *   }]
 * }
 */

import fs from "node:fs";
import path from "node:path";

const EPSILON = 1e-6;
const MAX_REPORT_ERRORS = 200;

function fail(message) {
  return { ok: false, message };
}

function text(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function finiteNonNegative(value, label) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? null
    : `${label} must be a finite non-negative number`;
}

function same(a, b) {
  return text(a)?.toLowerCase() === text(b)?.toLowerCase();
}

function addError(errors, record, message) {
  if (errors.length < MAX_REPORT_ERRORS) {
    errors.push({ runId: record?.runId ?? null, taskId: record?.taskId ?? null, message });
  }
}

function validateRecord(record, budgets, running, groups, errors) {
  if (!record || typeof record !== "object") {
    addError(errors, record, "record must be an object");
    return;
  }
  for (const field of ["runId", "taskId", "category", "complexity", "provider", "model"]) {
    if (!text(record[field])) addError(errors, record, `${field} is required`);
  }
  if (typeof record.validationPassed !== "boolean") {
    addError(errors, record, "validationPassed must be boolean");
  }
  const category = text(record.category)?.toUpperCase();
  const complexity = text(record.complexity)?.toUpperCase();
  const provider = text(record.provider)?.toLowerCase();
  const groupKey = `${category ?? "?"}|${complexity ?? "?"}`;
  groups[groupKey] ??= { category, complexity, records: 0, passed: 0, changed: 0 };
  groups[groupKey].records += 1;
  if (record.validationPassed === true) groups[groupKey].passed += 1;

  const route = record.route;
  if (!route || typeof route !== "object") {
    addError(errors, record, "route evidence is required");
  } else {
    for (const field of ["currentProvider", "currentModel", "selectedProvider", "selectedModel", "reasonCode"]) {
      if (!text(route[field])) addError(errors, record, `route.${field} is required`);
    }
    for (const field of ["changed", "providerAvailable", "qualityEvidenceAvailable"]) {
      if (typeof route[field] !== "boolean") addError(errors, record, `route.${field} must be boolean`);
    }
    if (route.changed === true) {
      groups[groupKey].changed += 1;
      if (same(route.currentProvider, route.selectedProvider) && same(route.currentModel, route.selectedModel)) {
        addError(errors, record, "route.changed=true but selected route equals current route");
      }
      if (route.providerAvailable !== true) addError(errors, record, "changed route is unavailable");
      if (route.qualityEvidenceAvailable !== true) addError(errors, record, "changed route lacks quality evidence");
      if (route.reasonCode === "" || route.reasonCode === "UNKNOWN") addError(errors, record, "changed route has no typed reason code");
    }
    if (typeof route.qualityScore !== "number" || !Number.isFinite(route.qualityScore) || route.qualityScore < 0 || route.qualityScore > 100) {
      addError(errors, record, "route.qualityScore must be a number in [0,100]");
    }
    if (!Number.isInteger(route.qualitySampleSize) || route.qualitySampleSize < 0) {
      addError(errors, record, "route.qualitySampleSize must be a non-negative integer");
    }
    if (route.qualityEvidenceAvailable === true && route.qualitySampleSize < 1) {
      addError(errors, record, "quality evidence requires a positive sample size");
    }
    const scope = route.qualityScope;
    if (!scope || !same(scope.category, category) || !same(scope.complexity, complexity)) {
      addError(errors, record, "qualityScope must exactly match record category and complexity");
    }
  }

  const spend = record.spend;
  if (!spend || typeof spend !== "object") {
    addError(errors, record, "spend reconciliation evidence is required");
    return;
  }
  for (const field of ["reservedUsd", "actualUsd", "providerReportedUsd", "cumulativeUsd", "remainingBudgetUsd"]) {
    const issue = finiteNonNegative(spend[field], `spend.${field}`);
    if (issue) addError(errors, record, issue);
  }
  if (spend.reconciled !== true) addError(errors, record, "spend.reconciled must be true");
  if (typeof spend.reservedUsd === "number" && typeof spend.actualUsd === "number" && spend.actualUsd > spend.reservedUsd + EPSILON) {
    addError(errors, record, "actual spend exceeds reserved spend");
  }
  if (typeof spend.actualUsd === "number" && typeof spend.providerReportedUsd === "number" && Math.abs(spend.actualUsd - spend.providerReportedUsd) > Math.max(0.01, spend.actualUsd * 0.05)) {
    addError(errors, record, "provider-reported spend is not reconciled with actual spend");
  }
  if (provider) {
    const prior = running[provider] ?? 0;
    if (spend.cumulativeUsd + EPSILON < prior) addError(errors, record, "cumulative provider spend moved backwards");
    const expected = prior + spend.actualUsd;
    if (Math.abs(spend.cumulativeUsd - expected) > 0.01) addError(errors, record, `cumulative provider spend does not reconcile (expected ${expected.toFixed(6)})`);
    running[provider] = spend.cumulativeUsd;
    const budget = budgets[provider];
    if (budget === undefined) addError(errors, record, `provider budget missing for ${provider}`);
    else if (spend.cumulativeUsd > budget + EPSILON) addError(errors, record, `provider spend exceeds configured budget for ${provider}`);
  }
}

export function validateCohort(input) {
  const errors = [];
  if (!input || typeof input !== "object") return { status: "FAIL", valid: false, errors: [{ message: "input must be an object" }] };
  if (input.schemaVersion !== 1) errors.push({ message: "schemaVersion must equal 1" });
  if (!input.providerBudgetsUsd || typeof input.providerBudgetsUsd !== "object" || Array.isArray(input.providerBudgetsUsd)) {
    errors.push({ message: "providerBudgetsUsd is required" });
  }
  if (!Array.isArray(input.records) || input.records.length === 0) {
    errors.push({ message: "records must be a non-empty array" });
  }
  const budgets = input.providerBudgetsUsd ?? {};
  const running = {};
  const groups = {};
  if (Array.isArray(input.records)) {
    input.records.forEach((record) => validateRecord(record, budgets, running, groups, errors));
  }
  const report = {
    schemaVersion: 1,
    status: errors.length === 0 ? "PASS" : "FAIL",
    valid: errors.length === 0,
    records: Array.isArray(input.records) ? input.records.length : 0,
    errors,
    errorCount: errors.length,
    groups: Object.values(groups),
    providerSpend: running,
    checks: {
      typedAvailabilityAndQuality: errors.length === 0,
      routeSwitchReasons: errors.length === 0,
      spendReconciliation: errors.length === 0,
      categoryComplexitySeparation: errors.length === 0,
    },
  };
  return report;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const inputPath = process.argv[2];
  const outputPath = process.argv[3];
  if (!inputPath) {
    console.error("usage: node scripts/orchestrator-routing-cohort.mjs INPUT.json [REPORT.json]");
    process.exit(2);
  }
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(path.resolve(inputPath), "utf8"));
  } catch (error) {
    console.error(`unable to read routing cohort: ${error.message}`);
    process.exit(2);
  }
  const report = validateCohort(parsed);
  const rendered = `${JSON.stringify(report, null, 2)}\n`;
  if (outputPath) fs.writeFileSync(path.resolve(outputPath), rendered);
  process.stdout.write(rendered);
  process.exit(report.valid ? 0 : 1);
}
