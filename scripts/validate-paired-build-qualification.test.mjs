import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { validateAuthorizedEnvironment, validatePlan } from "./validate-paired-build-qualification.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const plan = JSON.parse(fs.readFileSync(path.join(root, "scripts/build-qualification/paired-build-v1.json"), "utf8"));

test("committed qualification creates only two candidate runs at five dollars each", () => {
  assert.deepEqual(validatePlan(plan), []);
});

test("case or evaluator drift fails closed", () => {
  const drifted = structuredClone(plan);
  drifted.cases[0].specSha256 = "0".repeat(64);
  drifted.harness.sha256 = "1".repeat(64);
  const errors = validatePlan(drifted);
  assert.ok(errors.some((error) => error.includes("spec digest drifted")));
  assert.ok(errors.some((error) => error.includes("harness digest drifted")));
});

test("plan rejects fresh controls, missing negative evidence, and a larger spend matrix", () => {
  const weakened = structuredClone(plan);
  weakened.arms[0].source = "LIVE_CANDIDATE";
  weakened.requiredRolledBackEvidence = weakened.requiredRolledBackEvidence.filter((field) => field !== "rejectedDiffSha256");
  weakened.routes[0].safeMaxUsdPerRun = 6;
  const errors = validatePlan(weakened);
  assert.ok(errors.some((error) => error.includes("historical immutable")));
  assert.ok(errors.some((error) => error.includes("rejectedDiffSha256")));
  assert.ok(errors.some((error) => error.includes("exactly $5")));
});

test("authorized preflight permits only the dev app deployment and immutable candidate SHA", () => {
  const good = { BUILD_QUAL_CANDIDATE_URL: "https://app.executespec.ai", BUILD_QUAL_CANDIDATE_SHA: "b".repeat(40) };
  assert.deepEqual(validateAuthorizedEnvironment(plan, good), []);
  assert.ok(validateAuthorizedEnvironment(plan, { ...good, BUILD_QUAL_CANDIDATE_URL: "https://benchmark.executespec.ai" })
    .some((error) => error.includes("app.executespec.ai")));
  assert.ok(validateAuthorizedEnvironment(plan, { ...good, BUILD_QUAL_CANDIDATE_SHA: "dev" })
    .some((error) => error.includes("40/64-char")));
});
