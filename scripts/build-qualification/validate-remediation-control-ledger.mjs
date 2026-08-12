#!/usr/bin/env node

import { readFile } from "node:fs/promises";

const path =
  process.argv[2] ??
  new URL("./remediation-control-ledger-v1.json", import.meta.url).pathname;
const ledger = JSON.parse(await readFile(path, "utf8"));

const requiredControlFields = [
  "id",
  "phase",
  "invariantId",
  "authoritativeStore",
  "writer",
  "reconciler",
  "fixture",
  "owner",
  "reviewer",
  "status",
];

if (ledger.schemaVersion !== 1) throw new Error("unsupported remediation ledger schema");
if (!Array.isArray(ledger.controls)) throw new Error("controls must be an array");
const expected = Array.from({ length: 17 }, (_, index) => `R${index + 1}`);
const actual = ledger.controls.map((control) => control.id);
if (JSON.stringify(actual) !== JSON.stringify(expected)) {
  throw new Error(`controls must be exactly ${expected.join(",")}`);
}
for (const control of ledger.controls) {
  for (const field of requiredControlFields) {
    if (typeof control[field] !== "string" || control[field].trim() === "") {
      throw new Error(`${control.id}.${field} must be non-empty text`);
    }
  }
}
if (ledger.containment?.seededValue !== false) {
  throw new Error("shared BUILD containment must remain false");
}
if (ledger.operationalObjectives?.terminalConvergenceUpperBoundSeconds !== 300) {
  throw new Error("terminal convergence objective must match the frozen Phase 1 budget");
}
const expectedOpenGates = [
  "GATE_HISTORICAL_NULL_DISPOSITION",
  "GATE_RUNTIME_CONTAINMENT_AND_SCHEMA_ATTESTATION",
  "GATE_TYPED_DISPOSABLE_VERIFICATION",
];
if (
  !Array.isArray(ledger.openReleaseGates) ||
  JSON.stringify(ledger.openReleaseGates.map((gate) => gate.id)) !==
    JSON.stringify(expectedOpenGates)
) {
  throw new Error("open release gates must match the frozen remediation pass");
}
for (const gate of ledger.openReleaseGates) {
  if (gate.status !== "OPEN" || typeof gate.reason !== "string" || gate.reason.trim() === "") {
    throw new Error(`${gate.id} must remain OPEN with a non-empty reason`);
  }
}

process.stdout.write(
  JSON.stringify(
    {
      valid: true,
      programId: ledger.programId,
      controls: ledger.controls.length,
      openReleaseGates: ledger.openReleaseGates.map((gate) => gate.id),
      inProgress: ledger.controls.filter((control) => control.status === "IN_PROGRESS").map((c) => c.id),
    },
    null,
    2,
  ) + "\n",
);
