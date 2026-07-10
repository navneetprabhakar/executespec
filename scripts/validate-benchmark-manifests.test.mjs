import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { validateManifest } from "./validate-benchmark-manifests.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const manifests = path.join(root, "infra", "benchmark", "manifests");

test("committed benchmark manifests satisfy cross-field validation", () => {
  for (const file of fs.readdirSync(manifests).filter((name) => name.endsWith(".json") && !name.endsWith(".schema.json"))) {
    const value = JSON.parse(fs.readFileSync(path.join(manifests, file), "utf8"));
    assert.deepEqual(validateManifest(value, file), []);
  }
});

test("reproducible status rejects missing identities and contradictory rates", () => {
  const invalid = {
    schemaVersion: 1,
    status: "REPRODUCIBLE",
    suite: "suite",
    repository: { sha: null },
    model: { provider: "deepseek", matrixId: "m", providerSlug: null },
    prompt: { sha256: null },
    toolSchema: { sha256: null },
    harness: { sha256: null },
    dataset: { sha256: null },
    featureFlags: {},
    budgets: Object.fromEntries([
      "passes",
      "tasks",
      "concurrency",
      "contextTokens",
      "maxOutputTokens",
      "iterationLimit",
      "wallClockLimitSeconds",
      "costLimitUsd",
    ].map((key) => [key, null])),
    results: { resolved: 1, total: 2, resolutionRate: 1 },
  };
  const errors = validateManifest(invalid);
  assert.ok(errors.some((error) => error.includes("resolutionRate")));
  assert.ok(errors.some((error) => error.includes("repository.sha cannot be null")));
  assert.ok(errors.some((error) => error.includes("budgets.contextTokens cannot be null")));
});

test("benchmark validation rejects a missing resolution rate", () => {
  const manifest = {
    schemaVersion: 1,
    status: "EXPLORATORY",
    suite: "suite",
    model: { provider: "deepseek", matrixId: "m", providerSlug: null },
    repository: { sha: null },
    prompt: { sha256: null },
    toolSchema: { sha256: null },
    harness: { sha256: null },
    dataset: { sha256: null },
    featureFlags: {},
    budgets: Object.fromEntries(
      [
        "passes",
        "tasks",
        "concurrency",
        "contextTokens",
        "maxOutputTokens",
        "iterationLimit",
        "wallClockLimitSeconds",
        "costLimitUsd",
      ].map((key) => [key, 1]),
    ),
    results: { resolved: 0, total: 0 },
  };
  assert.ok(
    validateManifest(manifest).some((error) => error.includes("finite number")),
  );
});

test("benchmark validation rejects malformed reproducibility identities", () => {
  const manifest = {
    schemaVersion: 1,
    status: "EXPLORATORY",
    suite: "suite",
    repository: { sha: "not-a-sha" },
    model: { provider: "deepseek", matrixId: "m", providerSlug: "DeepSeek V4" },
    prompt: { sha256: "ABC" },
    toolSchema: { sha256: null },
    harness: { sha256: "0".repeat(64) },
    dataset: { name: "d", slice: "s", sha256: null },
    featureFlags: {},
    budgets: Object.fromEntries(
      [
        "passes",
        "tasks",
        "concurrency",
        "contextTokens",
        "maxOutputTokens",
        "iterationLimit",
        "wallClockLimitSeconds",
        "costLimitUsd",
      ].map((key) => [key, 1]),
    ),
    results: { resolved: 0, total: 0, resolutionRate: 0 },
  };
  const errors = validateManifest(manifest);
  assert.ok(errors.some((error) => error.includes("repository.sha must be null or a lowercase SHA-256")));
  assert.ok(errors.some((error) => error.includes("prompt.sha256 must be null or a lowercase SHA-256")));
  assert.ok(errors.some((error) => error.includes("model.providerSlug must be a lowercase provider slug or null")));
});
