import test from "node:test";
import assert from "node:assert/strict";

import { validateCohort } from "./orchestrator-routing-cohort.mjs";

function record(overrides = {}) {
  return {
    runId: "run-1",
    taskId: "task-1",
    category: "BUG_FIX",
    complexity: "LOW",
    provider: "anthropic",
    model: "claude-sonnet-4-6",
    validationPassed: true,
    route: {
      currentProvider: "openai",
      currentModel: "gpt-5.4",
      selectedProvider: "anthropic",
      selectedModel: "claude-sonnet-4-6",
      changed: true,
      reasonCode: "QUALITY_PRIOR",
      providerAvailable: true,
      qualityEvidenceAvailable: true,
      qualityScore: 92,
      qualitySampleSize: 10,
      qualityScope: { category: "BUG_FIX", complexity: "LOW" },
    },
    spend: {
      reservedUsd: 1,
      actualUsd: 0.8,
      providerReportedUsd: 0.8,
      reconciled: true,
      cumulativeUsd: 0.8,
      remainingBudgetUsd: 9.2,
    },
    ...overrides,
  };
}

test("passes a typed quality/availability/spend/category cohort", () => {
  const report = validateCohort({
    schemaVersion: 1,
    providerBudgetsUsd: { anthropic: 10 },
    records: [record()],
  });
  assert.equal(report.status, "PASS");
  assert.equal(report.valid, true);
  assert.deepEqual(report.providerSpend, { anthropic: 0.8 });
  assert.deepEqual(report.groups[0], {
    category: "BUG_FIX",
    complexity: "LOW",
    records: 1,
    passed: 1,
    changed: 1,
  });
});

test("fails closed when a changed route lacks typed quality/availability evidence", () => {
  const invalid = record({
    route: {
      ...record().route,
      providerAvailable: false,
      qualityEvidenceAvailable: false,
      qualitySampleSize: 0,
      qualityScope: { category: "TEST_FIX", complexity: "HIGH" },
    },
  });
  const report = validateCohort({
    schemaVersion: 1,
    providerBudgetsUsd: { anthropic: 10 },
    records: [invalid],
  });
  assert.equal(report.status, "FAIL");
  assert.ok(report.errors.some(({ message }) => message.includes("changed route is unavailable")));
  assert.ok(report.errors.some(({ message }) => message.includes("qualityScope")));
});

test("fails closed on spend mismatch, missing provider budget, and category contamination", () => {
  const invalid = record({
    category: "TEST_FIX",
    route: {
      ...record().route,
      qualityScope: { category: "BUG_FIX", complexity: "LOW" },
    },
    spend: {
      ...record().spend,
      actualUsd: 2,
      providerReportedUsd: 0.1,
      cumulativeUsd: 2,
    },
  });
  const report = validateCohort({ schemaVersion: 1, providerBudgetsUsd: {}, records: [invalid] });
  assert.equal(report.status, "FAIL");
  assert.ok(report.errors.some(({ message }) => message.includes("provider-reported spend")));
  assert.ok(report.errors.some(({ message }) => message.includes("provider budget missing")));
  assert.ok(report.errors.some(({ message }) => message.includes("qualityScope")));
});

test("rejects empty cohorts and unknown schema versions before inferring quality", () => {
  const report = validateCohort({ schemaVersion: 99, providerBudgetsUsd: {}, records: [] });
  assert.equal(report.status, "FAIL");
  assert.ok(report.errors.some(({ message }) => message.includes("schemaVersion")));
  assert.ok(report.errors.some(({ message }) => message.includes("non-empty array")));
});
