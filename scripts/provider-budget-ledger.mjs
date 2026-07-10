#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, "..");
const configPath = path.join(repoRoot, "config", "provider-test-budgets.json");
const goalStateDir = path.join(
  os.homedir(),
  ".local",
  "state",
  "executespec",
  "goals",
  "executespec-2.0-cli-1.0",
);
const testOverride = process.env.NODE_ENV === "test" && process.env.EXECUTESPEC_PROVIDER_LEDGER_TEST;
const ledgerPath = path.resolve(testOverride || path.join(goalStateDir, "provider-spend-ledger.json"));
const lockPath = `${ledgerPath}.lock`;
const MICROS_PER_USD = 1_000_000;
const EXPECTED_PROVIDERS = [
  "anthropic",
  "openai",
  "gemini",
  "deepseek",
  "openrouter",
  "fireworks",
];

function die(message, code = 1) {
  process.stderr.write(`${message}\n`);
  process.exit(code);
}

function parseArgs(argv) {
  const [command = "status", ...rest] = argv;
  const values = {};
  for (let i = 0; i < rest.length; i += 1) {
    const token = rest[i];
    if (!token.startsWith("--")) die(`Unexpected argument: ${token}`);
    const key = token.slice(2);
    const value = rest[i + 1];
    if (value === undefined || value.startsWith("--")) values[key] = true;
    else {
      values[key] = value;
      i += 1;
    }
  }
  return { command, values };
}

function micros(value, label, allowZero = true) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || (!allowZero && parsed === 0)) {
    die(`${label} must be ${allowZero ? "a non-negative" : "a positive"} number`);
  }
  // Always round charges and reservations upward. A sub-micro-dollar value costs one micro-dollar.
  return Math.ceil(parsed * MICROS_PER_USD - Number.EPSILON);
}

function usd(valueMicros) {
  return valueMicros / MICROS_PER_USD;
}

function count(value, label) {
  const parsed = Number(value ?? 0);
  if (!Number.isSafeInteger(parsed) || parsed < 0) die(`${label} must be a non-negative integer`);
  return parsed;
}

function loadConfig() {
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  if (config.schemaVersion !== 1 || config.currency !== "USD") die("Unsupported provider budget config");
  const actual = Object.keys(config.providers || {}).sort();
  if (JSON.stringify(actual) !== JSON.stringify([...EXPECTED_PROVIDERS].sort())) {
    die(`Provider budget config must contain exactly: ${EXPECTED_PROVIDERS.join(", ")}`);
  }
  if (!Array.isArray(config.forbiddenProviders) || !config.forbiddenProviders.includes("sarvam")) {
    die("Provider budget config must explicitly forbid sarvam");
  }
  const totalMicros = Object.entries(config.providers).reduce(
    (sum, [provider, cap]) => sum + micros(cap, `cap for ${provider}`, false),
    0,
  );
  if (totalMicros > 60 * MICROS_PER_USD) die("Aggregate provider cap exceeds $60");
  return config;
}

function initialLedger(config) {
  const providers = {};
  for (const [provider, capUsd] of Object.entries(config.providers)) {
    providers[provider] = {
      capMicros: micros(capUsd, `cap for ${provider}`, false),
      spentMicros: 0,
      reservedMicros: 0,
      locked: false,
      calls: [],
    };
  }
  return {
    schemaVersion: 2,
    goal: "executespec-2.0-cli-1.0",
    currency: "USD",
    unit: "micro-usd",
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    providers,
    reservations: {},
  };
}

function loadLedger(config) {
  if (!fs.existsSync(ledgerPath)) {
    die(`Provider ledger does not exist. Initialize once with: ${path.basename(process.argv[1])} init`);
  }
  const ledger = JSON.parse(fs.readFileSync(ledgerPath, "utf8"));
  if (ledger.schemaVersion !== 2 || ledger.goal !== "executespec-2.0-cli-1.0") {
    die("Provider ledger has an unsupported schema or goal identity; refusing reset/migration");
  }
  for (const [provider, capUsd] of Object.entries(config.providers)) {
    const entry = ledger.providers?.[provider];
    if (!entry) die(`Ledger is missing provider ${provider}; refusing reset`);
    const expected = micros(capUsd, `cap for ${provider}`, false);
    if (entry.capMicros !== expected) {
      die(`Ledger cap drift for ${provider}; expected ${expected}, found ${entry.capMicros}`);
    }
  }
  return ledger;
}

function persist(ledger) {
  fs.mkdirSync(path.dirname(ledgerPath), { recursive: true, mode: 0o700 });
  ledger.updatedAt = new Date().toISOString();
  const temp = `${ledgerPath}.${process.pid}.tmp`;
  fs.writeFileSync(temp, `${JSON.stringify(ledger, null, 2)}\n`, { mode: 0o600 });
  fs.renameSync(temp, ledgerPath);
}

function lockMetadata() {
  return JSON.stringify({ pid: process.pid, createdAt: new Date().toISOString() });
}

function withLock(action) {
  fs.mkdirSync(path.dirname(ledgerPath), { recursive: true, mode: 0o700 });
  let fd;
  try {
    fd = fs.openSync(lockPath, "wx", 0o600);
    fs.writeFileSync(fd, lockMetadata());
  } catch (error) {
    if (error.code === "EEXIST") {
      die(`Provider ledger is locked: ${lockPath}. Use recover-lock only after verifying its PID is dead.`);
    }
    throw error;
  }
  const cleanup = () => {
    try {
      fs.closeSync(fd);
    } catch {
      // Normal finally may already have closed it.
    }
    fs.rmSync(lockPath, { force: true });
  };
  process.once("exit", cleanup);
  try {
    return action();
  } finally {
    process.removeListener("exit", cleanup);
    cleanup();
  }
}

function recoverLock() {
  if (!fs.existsSync(lockPath)) die(`No lock exists: ${lockPath}`);
  let metadata;
  try {
    metadata = JSON.parse(fs.readFileSync(lockPath, "utf8"));
  } catch {
    die("Lock metadata is unreadable; manual inspection required");
  }
  if (!Number.isSafeInteger(metadata.pid)) die("Lock PID is invalid; manual inspection required");
  try {
    process.kill(metadata.pid, 0);
    die(`Lock owner PID ${metadata.pid} is still alive; refusing recovery`);
  } catch (error) {
    if (error.code !== "ESRCH") throw error;
  }
  fs.rmSync(lockPath);
  process.stdout.write(`Recovered stale lock owned by dead PID ${metadata.pid}\n`);
}

function requireProvider(config, raw) {
  const provider = String(raw || "").toLowerCase();
  if (config.forbiddenProviders.includes(provider)) die(`${provider} is forbidden for the main program`);
  if (!(provider in config.providers)) die(`Unauthorized provider: ${provider || "<missing>"}`);
  return provider;
}

function remainingMicros(entry) {
  return Math.max(0, entry.capMicros - entry.spentMicros - entry.reservedMicros);
}

function status(ledger, json) {
  if (json) {
    process.stdout.write(`${JSON.stringify(ledger, null, 2)}\n`);
    return;
  }
  for (const [provider, entry] of Object.entries(ledger.providers)) {
    process.stdout.write(
      `${provider.padEnd(12)} spent=$${usd(entry.spentMicros).toFixed(6)} reserved=$${usd(entry.reservedMicros).toFixed(6)} remaining=$${usd(remainingMicros(entry)).toFixed(6)} cap=$${usd(entry.capMicros).toFixed(2)} locked=${entry.locked}\n`,
    );
  }
}

function reservation(ledger, id) {
  const value = ledger.reservations[String(id || "")];
  if (!value) die(`Unknown reservation: ${String(id || "<missing>")}`);
  return value;
}

function callRecord(reserved, values, chargedMicros, providerReportedMicros, outcome) {
  const entry = {
    provider: reserved.provider,
    model: reserved.model,
    run: reserved.run,
    reservationId: reserved.reservationId,
    requestId: reserved.requestId,
    outcome,
    inputTokens: count(values["input-tokens"], "--input-tokens"),
    outputTokens: count(values["output-tokens"], "--output-tokens"),
    cacheReadTokens: count(values["cache-read-tokens"], "--cache-read-tokens"),
    cacheWriteTokens: count(values["cache-write-tokens"], "--cache-write-tokens"),
    reasoningTokens: count(values["reasoning-tokens"], "--reasoning-tokens"),
    estimatedCostUsd: usd(micros(values["estimated-cost-usd"] ?? 0, "--estimated-cost-usd")),
    providerReportedCostUsd: providerReportedMicros === null ? null : usd(providerReportedMicros),
    chargedUsd: usd(chargedMicros),
    reconciledAt: new Date().toISOString(),
  };
  return entry;
}

const { command, values } = parseArgs(process.argv.slice(2));
const config = loadConfig();

if (command === "init") {
  withLock(() => {
    if (fs.existsSync(ledgerPath)) die(`Ledger already exists: ${ledgerPath}`);
    persist(initialLedger(config));
  });
  process.stdout.write(`${ledgerPath}\n`);
} else if (command === "recover-lock") {
  recoverLock();
} else if (command === "status") {
  status(loadLedger(config), values.json === true);
} else if (command === "reserve") {
  withLock(() => {
    const ledger = loadLedger(config);
    const provider = requireProvider(config, values.provider);
    const model = String(values.model || "").trim();
    const run = String(values.run || "").trim();
    if (!model) die("--model is required");
    if (!run) die("--run is required");
    const safeMaxMicros = micros(values["safe-max-usd"], "--safe-max-usd", false);
    const entry = ledger.providers[provider];
    if (entry.locked) die(`Budget denied for ${provider}: provider ledger is locked after a breach`);
    if (safeMaxMicros > remainingMicros(entry)) {
      die(
        `Budget denied for ${provider}: safe maximum $${usd(safeMaxMicros).toFixed(6)} exceeds remaining $${usd(remainingMicros(entry)).toFixed(6)}`,
        2,
      );
    }
    const reservationId = crypto.randomUUID();
    ledger.reservations[reservationId] = {
      reservationId,
      provider,
      model,
      run,
      safeMaxMicros,
      state: "RESERVED",
      reservedAt: new Date().toISOString(),
    };
    entry.reservedMicros += safeMaxMicros;
    persist(ledger);
    process.stdout.write(`${reservationId}\n`);
  });
} else if (command === "mark-dispatched") {
  withLock(() => {
    const ledger = loadLedger(config);
    const reserved = reservation(ledger, values.reservation);
    if (reserved.state !== "RESERVED") die(`Reservation is already ${reserved.state}`);
    const requestId = String(values["request-id"] || "").trim();
    if (!requestId) die("--request-id is required");
    reserved.state = "DISPATCHED";
    reserved.requestId = requestId;
    reserved.dispatchedAt = new Date().toISOString();
    persist(ledger);
  });
} else if (command === "authorize-dispatch") {
  withLock(() => {
    const ledger = loadLedger(config);
    const reserved = reservation(ledger, values.reservation);
    const provider = requireProvider(config, values.provider);
    const model = String(values.model || "").trim();
    const run = String(values.run || "").trim();
    if (!model || !run) die("--model and --run are required");
    if (reserved.provider !== provider || reserved.model !== model || reserved.run !== run) {
      die(
        `Reservation scope mismatch: expected ${reserved.provider}/${reserved.model}/${reserved.run}, got ${provider}/${model}/${run}`,
      );
    }
    if (reserved.state === "RESERVED") {
      reserved.state = "DISPATCHED";
      reserved.requestId = `goal-live-run:${run}`;
      reserved.dispatchedAt = new Date().toISOString();
      persist(ledger);
    } else if (reserved.state !== "DISPATCHED") {
      die(`Reservation cannot authorize dispatch from state ${reserved.state}`);
    }
    process.stdout.write(`${reserved.reservationId}\n`);
  });
} else if (command === "reconcile" || command === "close-unknown") {
  let breach = false;
  withLock(() => {
    const ledger = loadLedger(config);
    const reserved = reservation(ledger, values.reservation);
    if (reserved.state !== "DISPATCHED") die("Only a DISPATCHED reservation can be reconciled");
    const estimatedMicros =
      command === "close-unknown"
        ? reserved.safeMaxMicros
        : micros(values["estimated-cost-usd"], "--estimated-cost-usd");
    const providerReportedMicros =
      values["provider-reported-cost-usd"] === undefined
        ? null
        : micros(values["provider-reported-cost-usd"], "--provider-reported-cost-usd");
    const chargedMicros = Math.max(estimatedMicros, providerReportedMicros ?? 0);
    const entry = ledger.providers[reserved.provider];
    entry.reservedMicros -= reserved.safeMaxMicros;
    entry.spentMicros += chargedMicros;
    breach = chargedMicros > reserved.safeMaxMicros || entry.spentMicros > entry.capMicros;
    if (breach) entry.locked = true;
    const record = callRecord(
      reserved,
      { ...values, "estimated-cost-usd": usd(estimatedMicros) },
      chargedMicros,
      providerReportedMicros,
      command === "close-unknown" ? "UNKNOWN_CHARGED_AT_SAFE_MAX" : breach ? "RECONCILED_BREACH" : "RECONCILED",
    );
    record.cumulativeSpendUsd = usd(entry.spentMicros);
    record.remainingBudgetUsd = usd(remainingMicros(entry));
    record.breach = breach;
    entry.calls.push(record);
    delete ledger.reservations[reserved.reservationId];
    persist(ledger);
    process.stdout.write(`${JSON.stringify(record)}\n`);
  });
  if (breach) die("Provider cost exceeded its reservation/cap; actual cost was recorded and provider is locked", 3);
} else if (command === "cancel") {
  withLock(() => {
    const ledger = loadLedger(config);
    const reserved = reservation(ledger, values.reservation);
    if (reserved.state !== "RESERVED") {
      die("A dispatched reservation cannot be cancelled; reconcile or close-unknown instead");
    }
    ledger.providers[reserved.provider].reservedMicros -= reserved.safeMaxMicros;
    delete ledger.reservations[reserved.reservationId];
    persist(ledger);
  });
} else {
  die(
    "Usage: provider-budget-ledger.mjs init|status|reserve|mark-dispatched|authorize-dispatch|reconcile|close-unknown|cancel|recover-lock",
    64,
  );
}
