import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
  validateAuthorizedEnvironment,
  validatePlan,
} from "./validate-paired-build-qualification.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const plan = JSON.parse(
  fs.readFileSync(path.join(root, "scripts/build-qualification/paired-build-v1.json"), "utf8"),
);

test("committed paired BUILD qualification plan is internally reproducible", () => {
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

test("paired contract rejects one route and missing generation evidence", () => {
  const weakened = structuredClone(plan);
  weakened.routes = weakened.routes.slice(0, 1);
  weakened.requiredEvidence = weakened.requiredEvidence.filter(
    (field) => field !== "acceptedWorkspaceGeneration",
  );
  const errors = validatePlan(weakened);
  assert.ok(errors.some((error) => error.includes("at least two exact")));
  assert.ok(errors.some((error) => error.includes("acceptedWorkspaceGeneration")));
});

test("the complete four-run matrix cannot exceed the suite-level spend cap", () => {
  const overspend = structuredClone(plan);
  overspend.routes[0].safeMaxUsdPerRun = 6;
  assert.ok(validatePlan(overspend).some((error) => error.includes("exceeds safeMaxTotalUsd")));
});

test("authorized preflight permits two distinct immutable releases sequentially on one host", () => {
  const good = {
    BUILD_QUAL_CONTROL_URL: "https://app.executespec.ai",
    BUILD_QUAL_CONTROL_SHA: "a".repeat(40),
    BUILD_QUAL_CANDIDATE_URL: "https://app.executespec.ai",
    BUILD_QUAL_CANDIDATE_SHA: "b".repeat(40),
  };
  assert.deepEqual(validateAuthorizedEnvironment(plan, good), []);

  const invalid = { ...good, BUILD_QUAL_CANDIDATE_SHA: "a".repeat(40) };
  assert.ok(
    validateAuthorizedEnvironment(plan, invalid).some((error) => error.includes("must differ")),
  );

  const differentTarget = {
    ...good,
    BUILD_QUAL_CANDIDATE_URL: "https://benchmark.executespec.ai",
  };
  assert.ok(
    validateAuthorizedEnvironment(plan, differentTarget).some((error) => error.includes("URLs must match")),
  );
});
