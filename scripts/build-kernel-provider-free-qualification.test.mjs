import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { test } from "node:test";

test("provider-free ten-category A/B/C qualification passes declared gates", () => {
  const directory = fs.mkdtempSync(
    path.join(os.tmpdir(), "build-kernel-qualification-"),
  );
  const output = path.join(directory, "report.json");
  const result = spawnSync(
    process.execPath,
    ["scripts/build-kernel-provider-free-qualification.mjs"],
    {
      cwd: process.cwd(),
      env: { ...process.env, BUILD_KERNEL_QUALIFICATION_OUTPUT: output },
      encoding: "utf8",
    },
  );

  assert.equal(result.status, 0, result.stderr || result.stdout);
  const report = JSON.parse(fs.readFileSync(output, "utf8"));
  assert.equal(report.corpusSize, 10);
  assert.equal(report.qualificationKind, "PROVIDER_FREE_SCRIPTED_MECHANISM");
  assert.ok(report.upliftPercentagePoints >= 20);
  assert.deepEqual(report.invariants, {
    candidateWrongAcceptances: 0,
    candidateUnresolvedAttempts: 0,
    candidateRollbackMismatches: 0,
    patchRegressions: 0,
  });
  assert.equal(report.rates.B, 1);
  assert.equal(report.rates.C, 1);
  assert.ok(
    report.results.every((row) => row.B.rollbacks === row.B.exactRollbacks),
  );
  assert.ok(
    report.results.every(
      (row) => row.B.modelCalls === 0 && row.B.costUsd === 0,
    ),
  );
});
