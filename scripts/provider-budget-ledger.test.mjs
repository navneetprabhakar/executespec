import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { afterEach, beforeEach, test } from "node:test";
import { fileURLToPath } from "node:url";

const script = path.join(path.dirname(fileURLToPath(import.meta.url)), "provider-budget-ledger.mjs");
let tempDir;
let ledger;

beforeEach(() => {
  tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "es-provider-ledger-"));
  ledger = path.join(tempDir, "ledger.json");
});

afterEach(() => fs.rmSync(tempDir, { recursive: true, force: true }));

function run(...args) {
  return spawnSync(process.execPath, [script, ...args], {
    encoding: "utf8",
    env: {
      ...process.env,
      NODE_ENV: "test",
      EXECUTESPEC_PROVIDER_LEDGER_TEST: ledger,
    },
  });
}

function reserve(provider = "deepseek", safeMaxUsd = "0.5") {
  return run(
    "reserve",
    "--provider",
    provider,
    "--model",
    `${provider}-model`,
    "--run",
    "transport-smoke",
    "--safe-max-usd",
    safeMaxUsd,
  );
}

function dispatch(id) {
  return run("mark-dispatched", "--reservation", id, "--request-id", `request-${id}`);
}

test("missing ledger fails closed and cannot be reset by reserve", () => {
  const attempt = reserve();
  assert.equal(attempt.status, 1);
  assert.match(attempt.stderr, /does not exist/);
  assert.equal(fs.existsSync(ledger), false);
});

test("rejects forbidden and unauthorized providers", () => {
  assert.equal(run("init").status, 0);
  const sarvam = reserve("sarvam", "0.1");
  assert.equal(sarvam.status, 1);
  assert.match(sarvam.stderr, /forbidden/);
  const unknown = reserve("other", "0.1");
  assert.equal(unknown.status, 1);
  assert.match(unknown.stderr, /Unauthorized provider/);
});

test("requires dispatch identity, reconciles conservatively, and never resets spend", () => {
  assert.equal(run("init").status, 0);
  const held = reserve();
  assert.equal(held.status, 0, held.stderr);
  const id = held.stdout.trim();
  const premature = run("reconcile", "--reservation", id, "--estimated-cost-usd", "0.1");
  assert.equal(premature.status, 1);
  assert.match(premature.stderr, /DISPATCHED/);
  assert.equal(dispatch(id).status, 0);
  const reconcile = run(
    "reconcile",
    "--reservation",
    id,
    "--input-tokens",
    "100",
    "--output-tokens",
    "20",
    "--cache-read-tokens",
    "5",
    "--reasoning-tokens",
    "7",
    "--estimated-cost-usd",
    "0.1",
    "--provider-reported-cost-usd",
    "0.12",
  );
  assert.equal(reconcile.status, 0, reconcile.stderr);
  const record = JSON.parse(reconcile.stdout);
  assert.equal(record.chargedUsd, 0.12);
  assert.equal(record.remainingBudgetUsd, 9.88);
  assert.notEqual(run("init").status, 0);
  const persisted = JSON.parse(fs.readFileSync(ledger, "utf8"));
  assert.equal(persisted.providers.deepseek.spentMicros, 120_000);
  assert.equal(persisted.providers.deepseek.calls.length, 1);
});

test("authorizes only the exact reserved provider, model, and run scope", () => {
  assert.equal(run("init").status, 0);
  const held = reserve("deepseek", "1");
  const id = held.stdout.trim();
  const mismatch = run(
    "authorize-dispatch",
    "--reservation",
    id,
    "--provider",
    "openai",
    "--model",
    "openai-model",
    "--run",
    "transport-smoke",
  );
  assert.equal(mismatch.status, 1);
  assert.match(mismatch.stderr, /scope mismatch/);
  const authorized = run(
    "authorize-dispatch",
    "--reservation",
    id,
    "--provider",
    "deepseek",
    "--model",
    "deepseek-model",
    "--run",
    "transport-smoke",
  );
  assert.equal(authorized.status, 0, authorized.stderr);
  const repeat = run(
    "authorize-dispatch",
    "--reservation",
    id,
    "--provider",
    "deepseek",
    "--model",
    "deepseek-model",
    "--run",
    "transport-smoke",
  );
  assert.equal(repeat.status, 0, repeat.stderr);
});

test("denies a request whose safe maximum exceeds provider headroom", () => {
  assert.equal(run("init").status, 0);
  assert.equal(reserve("openrouter", "9.75").status, 0);
  const denied = reserve("openrouter", "0.250001");
  assert.equal(denied.status, 2);
  assert.match(denied.stderr, /Budget denied/);
});

test("records an over-reservation charge and locks the provider", () => {
  assert.equal(run("init").status, 0);
  const held = reserve("anthropic", "0.1");
  const id = held.stdout.trim();
  assert.equal(dispatch(id).status, 0);
  const breached = run(
    "reconcile",
    "--reservation",
    id,
    "--estimated-cost-usd",
    "0.11",
  );
  assert.equal(breached.status, 3);
  const persisted = JSON.parse(fs.readFileSync(ledger, "utf8"));
  assert.equal(persisted.providers.anthropic.spentMicros, 110_000);
  assert.equal(persisted.providers.anthropic.locked, true);
  assert.equal(persisted.providers.anthropic.calls[0].outcome, "RECONCILED_BREACH");
  assert.match(reserve("anthropic", "0.01").stderr, /locked/);
});

test("cannot cancel after dispatch and close-unknown charges the full safe maximum", () => {
  assert.equal(run("init").status, 0);
  const held = reserve("gemini", "0.25");
  const id = held.stdout.trim();
  assert.equal(dispatch(id).status, 0);
  assert.match(run("cancel", "--reservation", id).stderr, /cannot be cancelled/);
  const closed = run("close-unknown", "--reservation", id);
  assert.equal(closed.status, 0, closed.stderr);
  assert.equal(JSON.parse(closed.stdout).chargedUsd, 0.25);
});

test("rounds every positive sub-micro-dollar reservation upward", () => {
  assert.equal(run("init").status, 0);
  const held = reserve("fireworks", "0.0000001");
  assert.equal(held.status, 0, held.stderr);
  const persisted = JSON.parse(fs.readFileSync(ledger, "utf8"));
  assert.equal(persisted.providers.fireworks.reservedMicros, 1);
});

test("concurrent reservations cannot both consume the same remaining headroom", async () => {
  assert.equal(run("init").status, 0);
  const args = [
    script,
    "reserve",
    "--provider",
    "openai",
    "--model",
    "gpt",
    "--run",
    "parallel",
    "--safe-max-usd",
    "6",
  ];
  const environment = {
    ...process.env,
    NODE_ENV: "test",
    EXECUTESPEC_PROVIDER_LEDGER_TEST: ledger,
  };
  const invoke = () =>
    new Promise((resolve) => {
      const child = spawn(process.execPath, args, { env: environment });
      child.on("exit", (code) => resolve(code));
    });
  const results = await Promise.all([invoke(), invoke()]);
  assert.equal(results.filter((code) => code === 0).length, 1);
  const persisted = JSON.parse(fs.readFileSync(ledger, "utf8"));
  assert.equal(persisted.providers.openai.reservedMicros, 6_000_000);
});
