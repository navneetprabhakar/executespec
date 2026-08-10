import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { validateResultRecords } from "./validate-paired-build-results.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const plan = JSON.parse(
  fs.readFileSync(path.join(root, "scripts/build-qualification/paired-build-v1.json"), "utf8"),
);

function completeEvidence() {
  const records = [];
  const reviews = new Map();
  for (const [armIndex, arm] of plan.arms.entries()) {
    for (const route of plan.routes) {
      for (const testCase of plan.cases) {
        const key = `${arm.id}/${route.provider}/${route.model}/${testCase.id}`;
        const record = {
          qualificationArm: arm.id,
          releaseSha: (armIndex ? "b" : "a").repeat(40),
          case: testCase.id,
          runId: `${arm.id}-${route.provider}`,
          terminalStatus: "COMPLETE",
          requestedProvider: route.provider,
          requestedModel: route.model,
          provider: route.provider.toUpperCase(),
          model: route.model,
          costUsd: 0.5,
          acceptedWorkspaceGeneration: "c".repeat(64),
          latestWorkspaceGenerationAttemptState: "ACCEPTED",
          unresolvedWorkspaceGenerationAttemptCount: 0,
          workspaceArchiveSha256: "d".repeat(64),
          heldOutPassed: 5,
          heldOutTotal: 5,
        };
        records.push(record);
        reviews.set(key, {
          verdict: "PASS",
          runId: record.runId,
          workspaceArchiveSha256: record.workspaceArchiveSha256,
          notes: "Independent review found complete idiomatic generated code.",
        });
      }
    }
  }
  return { records, reviews };
}

test("complete paired evidence requires every arm, route, case, held-out test, and manual review", () => {
  const { records, reviews } = completeEvidence();
  assert.deepEqual(validateResultRecords(plan, records, reviews), []);
});

test("self-score cannot hide route drift, unresolved generation state, or missing review", () => {
  const { records, reviews } = completeEvidence();
  records[0].provider = "OPENAI";
  records[0].unresolvedWorkspaceGenerationAttemptCount = 1;
  reviews.clear();
  const errors = validateResultRecords(plan, records, reviews);
  assert.ok(errors.some((error) => error.includes("effective route diverged")));
  assert.ok(errors.some((error) => error.includes("unresolved")));
  assert.ok(errors.some((error) => error.includes("independent generated-code review")));
});
