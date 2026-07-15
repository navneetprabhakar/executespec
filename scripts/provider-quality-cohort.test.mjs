import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { validateProviderQualityCohort } from "./provider-quality-cohort.mjs";

function fixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "es-quality-cohort-"));
  const paths = {};
  for (const provider of ["anthropic", "openai"]) {
    paths[provider] = {};
    for (const task of ["t1", "t2", "t3"]) {
      const relative = `${provider}/${task}.patch`;
      const target = path.join(dir, relative);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, `${provider}:${task}\n`);
      paths[provider][task] = { path: relative, sha256: crypto.createHash("sha256").update(fs.readFileSync(target)).digest("hex") };
    }
  }
  const records = (provider) => ["t1", "t2", "t3"].map((taskId) => ({
    taskId, outcome: "COMPLETED", commandExitCode: 0, artifact: paths[provider][taskId], heldOut: { passed: 2, total: 2 },
    spend: { actualUsd: 0, providerReportedUsd: 0, reconciled: true, cumulativeUsd: 0 },
  }));
  return { dir, input: { schemaVersion: 1, cohortId: "offline-fixture-v1", source: { fixtureSha256: "a".repeat(64), evaluatorSha256: "b".repeat(64), harnessSha256: "c".repeat(64) }, policy: { minTasksPerProvider: 3, providerBudgetsUsd: { anthropic: 10, openai: 10 } }, providers: [{ provider: "anthropic", model: "fixture-anthropic", tasks: records("anthropic") }, { provider: "openai", model: "fixture-openai", tasks: records("openai") }] } };
}

test("passes a matched k=3 artifact-level zero-spend cohort", () => {
  const { dir, input } = fixture();
  const report = validateProviderQualityCohort(input, dir);
  assert.equal(report.status, "PASS");
  assert.deepEqual(report.providers.map((p) => [p.provider, p.tasks, p.heldOutPassed, p.heldOutTotal]), [["anthropic", 3, 6, 6], ["openai", 3, 6, 6]]);
});

test("fails closed for fewer than three tasks and missing artifact", () => {
  const { dir, input } = fixture();
  input.providers[0].tasks = input.providers[0].tasks.slice(0, 2);
  input.providers[1].tasks[2].artifact.path = "missing.patch";
  const report = validateProviderQualityCohort(input, dir);
  assert.equal(report.status, "FAIL");
  assert.ok(report.errors.some((e) => e.message.includes("fewer than 3")));
  assert.ok(report.errors.some((e) => e.message.includes("artifact file is missing")));
});

test("fails closed when providers use different task sets or command fails", () => {
  const { dir, input } = fixture();
  input.providers[1].tasks[2].taskId = "other";
  input.providers[1].tasks[0].commandExitCode = 1;
  const report = validateProviderQualityCohort(input, dir);
  assert.equal(report.status, "FAIL");
  assert.ok(report.errors.some((e) => e.message.includes("same deterministic task-id set")));
  assert.ok(report.errors.some((e) => e.message.includes("command must exit")));
});
