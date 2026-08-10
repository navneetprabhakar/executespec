#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const SHA256 = /^[a-f0-9]{64}$/;

function tuple(record) {
  return `${record.qualificationArm}/${String(record.requestedProvider).toLowerCase()}/${record.requestedModel}/${record.case}`;
}

export function validateResultRecords(plan, records, reviews = new Map()) {
  const errors = [];
  const expected = new Set();
  for (const arm of plan.arms) {
    for (const route of plan.routes) {
      for (const testCase of plan.cases) {
        expected.add(`${arm.id}/${route.provider}/${route.model}/${testCase.id}`);
      }
    }
  }
  const seen = new Set();
  const releaseByArm = new Map();
  for (const record of records) {
    const key = tuple(record);
    if (!expected.has(key)) errors.push(`unexpected result tuple ${key}`);
    if (seen.has(key)) errors.push(`duplicate result tuple ${key}`);
    seen.add(key);
    if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(record.releaseSha || "")) {
      errors.push(`${key} releaseSha is invalid`);
    } else if (releaseByArm.has(record.qualificationArm)
        && releaseByArm.get(record.qualificationArm) !== record.releaseSha) {
      errors.push(`${key} releaseSha differs within one arm`);
    } else {
      releaseByArm.set(record.qualificationArm, record.releaseSha);
    }
    if (record.terminalStatus !== "COMPLETE") errors.push(`${key} did not reach COMPLETE`);
    if (String(record.provider).toLowerCase() !== String(record.requestedProvider).toLowerCase()
        || record.model !== record.requestedModel) {
      errors.push(`${key} effective route diverged from the paired exact route`);
    }
    if (!(Number.isFinite(record.costUsd) && record.costUsd >= 0)) errors.push(`${key} cost is invalid`);
    if (!SHA256.test(record.acceptedWorkspaceGeneration || "")) {
      errors.push(`${key} accepted workspace generation is missing`);
    }
    if (record.latestWorkspaceGenerationAttemptState !== "ACCEPTED") {
      errors.push(`${key} latest workspace-generation attempt is not ACCEPTED`);
    }
    if (record.unresolvedWorkspaceGenerationAttemptCount !== 0) {
      errors.push(`${key} has unresolved workspace-generation attempts`);
    }
    if (!SHA256.test(record.workspaceArchiveSha256 || "")) {
      errors.push(`${key} workspace archive digest is missing`);
    }
    if (!(Number.isInteger(record.heldOutTotal) && record.heldOutTotal > 0
        && record.heldOutPassed === record.heldOutTotal)) {
      errors.push(`${key} did not pass every held-out test`);
    }
    const review = reviews.get(key);
    if (!review || review.verdict !== "PASS" || review.runId !== record.runId
        || review.workspaceArchiveSha256 !== record.workspaceArchiveSha256
        || typeof review.notes !== "string" || review.notes.trim().length < 20) {
      errors.push(`${key} lacks an archive-bound independent generated-code review`);
    }
  }
  for (const key of expected) if (!seen.has(key)) errors.push(`missing result tuple ${key}`);
  if (releaseByArm.get("control") && releaseByArm.get("control") === releaseByArm.get("candidate")) {
    errors.push("control and candidate result releases are identical");
  }
  return errors;
}

function filesUnder(root, name) {
  if (!fs.existsSync(root)) return [];
  return fs.readdirSync(root, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name === name)
    .map((entry) => path.join(entry.parentPath, entry.name));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const planPath = path.resolve(process.argv[2] || path.join(scriptDir, "build-qualification/paired-build-v1.json"));
  const resultRoot = path.resolve(process.argv[3] || path.join(scriptDir, "build-qualification/results/build-workspace-generation-v1"));
  const plan = JSON.parse(fs.readFileSync(planPath, "utf8"));
  const records = filesUnder(resultRoot, "completed-runs.jsonl").flatMap((file) =>
    fs.readFileSync(file, "utf8").split("\n").filter(Boolean).map((line) => JSON.parse(line)),
  );
  const reviews = new Map();
  for (const file of filesUnder(path.join(resultRoot, "reviews"), "review.json")) {
    const review = JSON.parse(fs.readFileSync(file, "utf8"));
    reviews.set(review.tuple, review);
  }
  const errors = validateResultRecords(plan, records, reviews);
  if (errors.length) {
    process.stderr.write(`${errors.join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write(`Paired BUILD qualification passed ${records.length} independently reviewed run(s)\n`);
}
