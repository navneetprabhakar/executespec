#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const manifestDir = path.resolve(scriptDir, "../infra/benchmark/manifests");
const SHA256 = /^[a-f0-9]{64}$/;
const REQUIRED_BUDGETS = [
  "passes",
  "tasks",
  "concurrency",
  "contextTokens",
  "maxOutputTokens",
  "iterationLimit",
  "wallClockLimitSeconds",
  "costLimitUsd",
];

export function validateManifest(manifest, file = "<manifest>") {
  const errors = [];
  const add = (message) => errors.push(`${file}: ${message}`);
  if (manifest.schemaVersion !== 1) add("schemaVersion must be 1");
  if (!manifest.suite) add("suite is required");
  if (!manifest.model?.provider || !manifest.model?.matrixId) add("provider and matrixId are required");
  if (!manifest.results || !Number.isInteger(manifest.results.resolved) || !Number.isInteger(manifest.results.total)) {
    add("results.resolved and results.total must be integers");
  } else {
    if (manifest.results.resolved < 0 || manifest.results.resolved > manifest.results.total) {
      add("resolved must be between zero and total");
    }
    const expected = manifest.results.total === 0 ? 0 : manifest.results.resolved / manifest.results.total;
    if (Math.abs(expected - manifest.results.resolutionRate) > 1e-12) {
      add(`resolutionRate ${manifest.results.resolutionRate} does not equal ${expected}`);
    }
  }
  if (manifest.dataset?.sha256 !== null && !SHA256.test(manifest.dataset?.sha256 || "")) {
    add("dataset.sha256 must be null or a lowercase SHA-256");
  }
  if ("worker.run-journal.enabled" in (manifest.featureFlags || {})) {
    add("worker.run-journal.enabled is not a real flag; use execution.run-journal.enabled");
  }
  for (const field of REQUIRED_BUDGETS) {
    if (!(field in (manifest.budgets || {}))) add(`budgets.${field} is required`);
  }
  if (manifest.status === "REPRODUCIBLE") {
    const identities = [
      ["repository.sha", manifest.repository?.sha],
      ["model.providerSlug", manifest.model?.providerSlug],
      ["prompt.sha256", manifest.prompt?.sha256],
      ["toolSchema.sha256", manifest.toolSchema?.sha256],
      ["harness.sha256", manifest.harness?.sha256],
      ["dataset.sha256", manifest.dataset?.sha256],
    ];
    for (const [name, value] of identities) {
      if (!value) add(`${name} cannot be null for REPRODUCIBLE status`);
    }
    for (const field of REQUIRED_BUDGETS) {
      if (manifest.budgets?.[field] === null) add(`budgets.${field} cannot be null for REPRODUCIBLE status`);
    }
  }
  return errors;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const files = fs
    .readdirSync(manifestDir)
    .filter((file) => file.endsWith(".json") && !file.endsWith(".schema.json"))
    .sort();
  const errors = files.flatMap((file) => {
    const manifest = JSON.parse(fs.readFileSync(path.join(manifestDir, file), "utf8"));
    return validateManifest(manifest, file);
  });
  if (errors.length > 0) {
    process.stderr.write(`${errors.join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write(`Validated ${files.length} benchmark manifest(s)\n`);
}
