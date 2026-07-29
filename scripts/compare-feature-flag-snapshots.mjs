#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const PROTECTED_KEYS = new Set([
  "execution.goal-decision.enforcement.enabled",
  "preview.render-agent.enabled",
  "requirement.ir.gate.mode",
  "requirement.ir.shadow.enabled",
  "sandbox.lifecycle.enabled",
  "sandbox.runtime.container-strict-v1.enabled",
  "sandbox.runtime.dispatch.enabled",
  "sandbox.runtime.toolchain-readiness.enabled",
  "spec.chat.enabled",
  "testgen.live-preview-network.enabled",
  "worker.auto-verify.enabled",
  "worker.verify-before-complete.enabled",
]);

function usage() {
  return [
    "Usage: node scripts/compare-feature-flag-snapshots.mjs <local.json> <dev.json> [allowlist.json]",
    "",
    "Snapshots must be the JSON array returned by GET /api/admin/feature-flags, or an object",
    "containing that array under `flags`. This command is read-only: capture both snapshots",
    "separately with authenticated GET requests, then compare the files here.",
  ].join("\n");
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function normalizeSnapshot(raw, label) {
  const rows = Array.isArray(raw) ? raw : raw?.flags;
  if (!Array.isArray(rows))
    throw new Error(`${label} snapshot is not a feature-flag array`);
  const result = new Map();
  for (const row of rows) {
    const key = row?.key ?? row?.flagKey;
    const value = row?.value ?? row?.flagValue;
    const valueType = row?.valueType ?? null;
    const scope = row?.scope ?? null;
    if (typeof key !== "string" || !key.trim()) {
      throw new Error(`${label} snapshot contains a row without a key`);
    }
    if (value == null)
      throw new Error(`${label} snapshot flag ${key} has no value`);
    if (result.has(key))
      throw new Error(`${label} snapshot contains duplicate flag ${key}`);
    result.set(key, { value: String(value), valueType, scope });
  }
  return result;
}

function normalizeAllowlist(raw) {
  const rows = raw?.exceptions;
  if (!Array.isArray(rows))
    throw new Error("allowlist must contain an `exceptions` array");
  const result = new Map();
  for (const row of rows) {
    const key = row?.key;
    const fields = row?.fields;
    const reason = row?.reason;
    if (typeof key !== "string" || !key.trim())
      throw new Error("allowlist entry needs a key");
    if (!Array.isArray(fields) || fields.length === 0) {
      throw new Error(`allowlist entry ${key} needs one or more fields`);
    }
    if (
      !fields.every((field) =>
        ["presence", "value", "valueType", "scope"].includes(field),
      )
    ) {
      throw new Error(`allowlist entry ${key} contains an unsupported field`);
    }
    if (typeof reason !== "string" || reason.trim().length < 20) {
      throw new Error(
        `allowlist entry ${key} needs a specific rationale (at least 20 characters)`,
      );
    }
    if (PROTECTED_KEYS.has(key)) {
      throw new Error(`release-critical flag ${key} cannot be allowlisted`);
    }
    if (result.has(key)) throw new Error(`duplicate allowlist entry ${key}`);
    result.set(key, new Set(fields));
  }
  return result;
}

export function compareSnapshots(
  localRaw,
  devRaw,
  allowlistRaw = { exceptions: [] },
) {
  const local = normalizeSnapshot(localRaw, "local");
  const dev = normalizeSnapshot(devRaw, "dev");
  const allowlist = normalizeAllowlist(allowlistRaw);
  const keys = [...new Set([...local.keys(), ...dev.keys()])].sort();
  const mismatches = [];
  const allowed = [];
  for (const key of keys) {
    const a = local.get(key);
    const b = dev.get(key);
    const differences = [];
    if (!a || !b) {
      differences.push("presence");
    } else {
      if (a.value !== b.value) differences.push("value");
      if (a.valueType !== b.valueType) differences.push("valueType");
      if (a.scope !== b.scope) differences.push("scope");
    }
    for (const field of differences) {
      const row = {
        key,
        field,
        local: field === "presence" ? Boolean(a) : (a?.[field] ?? null),
        dev: field === "presence" ? Boolean(b) : (b?.[field] ?? null),
      };
      if (allowlist.get(key)?.has(field)) allowed.push(row);
      else mismatches.push(row);
    }
  }
  return {
    matched: mismatches.length === 0,
    compared: keys.length,
    mismatches,
    allowed,
  };
}

function main(argv) {
  if (argv.includes("--help") || argv.length < 2 || argv.length > 3) {
    process.stdout.write(`${usage()}\n`);
    return argv.includes("--help") ? 0 : 2;
  }
  const [localFile, devFile, allowlistFile] = argv.map((file) =>
    path.resolve(file),
  );
  const result = compareSnapshots(
    readJson(localFile),
    readJson(devFile),
    readJson(
      allowlistFile ??
        new URL("./feature-flag-parity-allowlist.json", import.meta.url),
    ),
  );
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  return result.matched ? 0 : 1;
}

if (
  process.argv[1] &&
  import.meta.url === new URL(`file://${path.resolve(process.argv[1])}`).href
) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(
      `${error instanceof Error ? error.message : String(error)}\n`,
    );
    process.exitCode = 2;
  }
}
