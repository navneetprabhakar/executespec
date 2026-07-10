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
