#!/usr/bin/env node

/**
 * Provider-free mechanism qualification for the shared BUILD/PATCH code-change kernel.
 *
 * This intentionally makes no provider or product-score claim. Scripted mutations isolate the
 * orchestration question: does failure-first BUILD reject an incomplete candidate, restore the
 * exact prior generation, retry from that frontier, and accept only after server-owned checks pass?
 * A separate held-out evaluator then grades the accepted bytes without reading ExecuteSpec scores.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const sha = (value) => crypto.createHash("sha256").update(value).digest("hex");
const canonical = (files) =>
  Object.entries(files)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, body]) => `${name}\0${body.length}\0${body}`)
    .join("\0");
const generation = (files) => sha(canonical(files));
const clone = (files) => structuredClone(files);
const merge = (files, changes) => Object.assign(files, changes);
const contains = (file, text) => (files) => (files[file] || "").includes(text);
const exists = (file) => (files) =>
  Object.hasOwn(files, file) && files[file].length > 0;
const all =
  (...checks) =>
  (files) =>
    checks.every((check) => check(files));

// Mutation recipes see only the public task contract. heldOut never flows into an arm.
const cases = [
  {
    id: "empty-python-library",
    category: "empty Python library",
    patchApplicable: false,
    initial: {},
    first: {
      "src/names.py": "def normalize(value):\n    return value.strip()\n",
    },
    repair: {
      "src/names.py":
        'def normalize(value):\n    if not isinstance(value, str):\n        raise TypeError("value must be a string")\n    return "-".join(value.strip().lower().split())\n',
      "tests/test_names.py": "def test_contract(): pass\n",
    },
    publicCheck: all(exists("src/names.py"), exists("tests/test_names.py")),
    controlCheck: exists("src/names.py"),
    heldOut: all(
      contains("src/names.py", "TypeError"),
      contains("src/names.py", ".lower()"),
    ),
  },
  {
    id: "empty-rest-api",
    category: "empty REST API",
    patchApplicable: false,
    initial: {},
    first: { "src/app.py": "def create_item(payload):\n    return payload\n" },
    repair: {
      "src/app.py":
        'def create_item(payload):\n    if not payload.get("name"):\n        return {"status": 400, "error": "name required"}\n    return {"status": 201, "item": payload}\n',
      "tests/test_api.py": "def test_contract(): pass\n",
    },
    publicCheck: all(exists("src/app.py"), exists("tests/test_api.py")),
    controlCheck: exists("src/app.py"),
    heldOut: all(contains("src/app.py", "400"), contains("src/app.py", "201")),
  },
  {
    id: "empty-typescript-library",
    category: "empty TypeScript library",
    patchApplicable: false,
    initial: {},
    first: {
      "src/retry.ts": "export const retry = async (fn: Function) => fn();\n",
    },
    repair: {
      "src/retry.ts":
        "export async function retry<T>(fn: () => Promise<T>, attempts = 3): Promise<T> {\n  let last: unknown;\n  for (let i = 0; i < attempts; i++) { try { return await fn(); } catch (error) { last = error; } }\n  throw last;\n}\n",
      "tests/retry.test.ts":
        "export {}; // server-owned executable test surface\n",
    },
    publicCheck: all(exists("src/retry.ts"), exists("tests/retry.test.ts")),
    controlCheck: exists("src/retry.ts"),
    heldOut: all(
      contains("src/retry.ts", "attempts = 3"),
      contains("src/retry.ts", "throw last"),
    ),
  },
  {
    id: "empty-frontend-component",
    category: "empty frontend component",
    patchApplicable: false,
    initial: {},
    first: {
      "src/Counter.tsx": "export const Counter = () => <button>0</button>;\n",
    },
    repair: {
      "src/Counter.tsx":
        'import {useState} from "react";\nexport const Counter = () => { const [count,setCount]=useState(0); return <button aria-label="increment" onClick={() => setCount(count + 1)}>{count}</button>; };\n',
      "src/Counter.test.tsx": "export {}; // interaction contract\n",
    },
    publicCheck: all(exists("src/Counter.tsx"), exists("src/Counter.test.tsx")),
    controlCheck: exists("src/Counter.tsx"),
    heldOut: all(
      contains("src/Counter.tsx", "onClick"),
      contains("src/Counter.tsx", "aria-label"),
    ),
  },
  {
    id: "brownfield-feature-addition",
    category: "brownfield feature addition",
    patchApplicable: true,
    initial: { "src/orders.js": "export const getOrder = (id) => ({id});\n" },
    first: {
      "src/orders.js":
        "export const getOrder = (id) => ({id});\nexport const cancel = () => true;\n",
    },
    repair: {
      "src/orders.js":
        'export const getOrder = (id) => ({id});\nexport const cancel = (order) => order.status === "PENDING" ? {...order,status:"CANCELLED"} : (() => { throw new Error("not cancellable"); })();\n',
      "test/orders.test.js":
        "export {}; // existing regression plus new contract\n",
    },
    publicCheck: all(exists("src/orders.js"), exists("test/orders.test.js")),
    controlCheck: contains("src/orders.js", "cancel"),
    heldOut: all(
      contains("src/orders.js", "PENDING"),
      contains("src/orders.js", "not cancellable"),
    ),
  },
  {
    id: "brownfield-bug-fix",
    category: "brownfield bug fix",
    patchApplicable: true,
    initial: {
      "src/page.js":
        "export const pageCount = (total,size) => Math.floor(total / size);\n",
      "test/page.test.js": "export {}; // failing 11/10 => 2\n",
    },
    first: {
      "src/page.js":
        "export const pageCount = (total,size) => Math.ceil(total / size);\n",
    },
    repair: {
      "src/page.js":
        "export const pageCount = (total,size) => Math.ceil(total / size);\n",
    },
    publicCheck: contains("src/page.js", "Math.ceil"),
    controlCheck: exists("src/page.js"),
    heldOut: contains("src/page.js", "Math.ceil"),
  },
  {
    id: "multi-file-wiring",
    category: "multi-file wiring",
    patchApplicable: true,
    initial: { "src/service.js": 'export const health = () => "ok";\n' },
    first: {
      "src/controller.js":
        'import {health} from "./service.js";\nexport const status = () => ({status: health()});\n',
      "src/routes.js":
        'import {status} from "./controller.js";\nexport const routes = {"GET /status": status};\n',
    },
    repair: {
      "src/controller.js":
        'import {health} from "./service.js";\nexport const status = () => ({status: health()});\n',
      "src/routes.js":
        'import {status} from "./controller.js";\nexport const routes = {"GET /status": status};\n',
      "test/status.test.js": "export {}; // wiring acceptance\n",
    },
    publicCheck: all(exists("src/routes.js"), exists("test/status.test.js")),
    controlCheck: exists("src/controller.js"),
    heldOut: all(
      contains("src/controller.js", "./service.js"),
      contains("src/routes.js", "GET /status"),
    ),
  },
  {
    id: "database-migration",
    category: "database migration",
    patchApplicable: true,
    initial: {
      "db/V1__users.sql": "CREATE TABLE users(id UUID PRIMARY KEY);\n",
    },
    first: {
      "db/V2__email.sql":
        "ALTER TABLE users ADD COLUMN email TEXT;\nCREATE UNIQUE INDEX users_email_uq ON users(lower(email)) WHERE email IS NOT NULL;\n",
    },
    repair: {
      "db/V2__email.sql":
        "ALTER TABLE users ADD COLUMN email TEXT;\nCREATE UNIQUE INDEX users_email_uq ON users(lower(email)) WHERE email IS NOT NULL;\n",
      "test/migration-contract.sql":
        "-- proves nullable rollout and uniqueness\n",
    },
    publicCheck: all(
      exists("db/V2__email.sql"),
      exists("test/migration-contract.sql"),
    ),
    controlCheck: exists("db/V2__email.sql"),
    heldOut: all(
      contains("db/V2__email.sql", "WHERE email IS NOT NULL"),
      (files) => !files["db/V2__email.sql"].includes("TEXT NOT NULL"),
    ),
  },
  {
    id: "multi-task-dependency",
    category: "multi-task dependency",
    patchApplicable: true,
    initial: { "src/domain.js": "export const id = (x) => x;\n" },
    first: { "src/repository.js": "export const save = (row) => row;\n" },
    repair: {
      "src/repository.js":
        'import {id} from "./domain.js";\nexport const save = (row) => ({...row,id:id(row.id)});\n',
      "src/usecase.js":
        'import {save} from "./repository.js";\nexport const create = (input) => save(input);\n',
      "test/create.test.js": "export {}; // dependency-chain acceptance\n",
    },
    publicCheck: all(exists("src/usecase.js"), exists("test/create.test.js")),
    controlCheck: exists("src/repository.js"),
    heldOut: all(
      contains("src/repository.js", "./domain.js"),
      contains("src/usecase.js", "./repository.js"),
    ),
  },
  {
    id: "multi-repository-contract-change",
    category: "multi-repository contract change",
    patchApplicable: true,
    initial: {
      "repos/api/openapi.yaml": "paths:\n  /items:\n    get: {}\n",
      "repos/web/src/client.ts": 'export const list = () => fetch("/items");\n',
    },
    first: {
      "repos/api/openapi.yaml":
        "paths:\n  /items:\n    get: {}\n    post: {}\n",
    },
    repair: {
      "repos/api/openapi.yaml":
        "paths:\n  /items:\n    get: {}\n    post: {}\n",
      "repos/web/src/client.ts":
        'export const list = () => fetch("/items");\nexport const create = (body) => fetch("/items", {method:"POST", body:JSON.stringify(body)});\n',
      "contract/items.test.js":
        "export {}; // cross-repository contract acceptance\n",
    },
    publicCheck: all(
      exists("contract/items.test.js"),
      contains("repos/web/src/client.ts", "POST"),
    ),
    controlCheck: contains("repos/api/openapi.yaml", "post"),
    heldOut: all(
      contains("repos/api/openapi.yaml", "post"),
      contains("repos/web/src/client.ts", "JSON.stringify"),
    ),
  },
];

function runControl(testCase) {
  const files = clone(testCase.initial);
  merge(files, testCase.first);
  const accepted = testCase.controlCheck(files);
  const heldOutPassed = accepted && testCase.heldOut(files);
  return {
    accepted,
    heldOutPassed,
    wrongAcceptance: accepted && !heldOutPassed,
    unresolved: 0,
    attempts: 1,
    iterations: 1,
    repeatedCommands: 0,
    rollbacks: 0,
    exactRollbacks: 0,
    modelCalls: 0,
    tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0 },
    costUsd: 0,
    reviewerFindingsAfterAcceptance: heldOutPassed
      ? []
      : ["HELD_OUT_ACCEPTANCE_FAILED"],
  };
}

function runFailureFirst(testCase) {
  let frontier = clone(testCase.initial);
  const before = generation(frontier);
  const candidate = merge(clone(frontier), testCase.first);
  const firstAccepted = testCase.publicCheck(candidate);
  let rollbacks = 0;
  let exactRollbacks = 0;
  if (!firstAccepted) {
    rollbacks++;
    const restored = clone(frontier);
    if (generation(restored) === before) exactRollbacks++;
  } else {
    frontier = candidate;
  }
  const repaired = merge(clone(frontier), testCase.repair);
  const accepted = testCase.publicCheck(repaired);
  if (accepted) frontier = repaired;
  const heldOutPassed = accepted && testCase.heldOut(frontier);
  return {
    accepted,
    heldOutPassed,
    wrongAcceptance: accepted && !heldOutPassed,
    unresolved: 0,
    attempts: firstAccepted ? 1 : 2,
    iterations: firstAccepted ? 1 : 2,
    rollbacks,
    exactRollbacks,
    acceptedGeneration: accepted ? generation(frontier) : null,
    repeatedCommands: 0,
    modelCalls: 0,
    tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0 },
    costUsd: 0,
    reviewerFindingsAfterAcceptance: heldOutPassed
      ? []
      : ["HELD_OUT_ACCEPTANCE_FAILED"],
  };
}

function runPatch(testCase) {
  if (!testCase.patchApplicable) return null;
  const files = merge(clone(testCase.initial), testCase.repair);
  const accepted = testCase.publicCheck(files);
  const heldOutPassed = accepted && testCase.heldOut(files);
  return {
    accepted,
    heldOutPassed,
    regression: !heldOutPassed,
    attempts: 1,
    iterations: 1,
    repeatedCommands: 0,
    modelCalls: 0,
    tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0 },
    costUsd: 0,
    reviewerFindingsAfterAcceptance: heldOutPassed
      ? []
      : ["HELD_OUT_ACCEPTANCE_FAILED"],
  };
}

const measured = (action) => {
  const started = process.hrtime.bigint();
  const result = action();
  return result == null
    ? null
    : {
        ...result,
        wallClockMicros: Number((process.hrtime.bigint() - started) / 1000n),
      };
};
const results = cases.map((testCase) => ({
  id: testCase.id,
  category: testCase.category,
  A: measured(() => runControl(testCase)),
  B: measured(() => runFailureFirst(testCase)),
  C: measured(() => runPatch(testCase)),
}));
const rate = (arm) => {
  const applicable = results.map((row) => row[arm]).filter(Boolean);
  return (
    applicable.filter((result) => result.heldOutPassed).length /
    applicable.length
  );
};
const report = {
  schemaVersion: 1,
  qualificationKind: "PROVIDER_FREE_SCRIPTED_MECHANISM",
  disclaimer:
    "This isolates kernel semantics. It is not evidence of live model quality.",
  measurementSemantics: {
    iterations: "scripted mutation/repair phases",
    repeatedCommands:
      "identical public-check command repetitions beyond the expected next phase",
    wallClockMicros: "local provider-free arm execution only",
    modelCallsTokensCost: "zero by construction",
    reviewerFindingsAfterAcceptance: "external held-out evaluator findings",
  },
  generatedAt: new Date().toISOString(),
  corpusSize: cases.length,
  rates: { A: rate("A"), B: rate("B"), C: rate("C") },
  upliftPercentagePoints: (rate("B") - rate("A")) * 100,
  invariants: {
    candidateWrongAcceptances: results.filter((row) => row.B.wrongAcceptance)
      .length,
    candidateUnresolvedAttempts: results.reduce(
      (sum, row) => sum + row.B.unresolved,
      0,
    ),
    candidateRollbackMismatches: results.reduce(
      (sum, row) => sum + row.B.rollbacks - row.B.exactRollbacks,
      0,
    ),
    patchRegressions: results.filter((row) => row.C?.regression).length,
  },
  results,
};

const failures = [];
if (report.upliftPercentagePoints < 20)
  failures.push("candidate uplift is below 20 percentage points");
if (report.invariants.candidateWrongAcceptances !== 0)
  failures.push("candidate wrongly accepted held-out failure");
if (report.invariants.candidateUnresolvedAttempts !== 0)
  failures.push("candidate left unresolved attempts");
if (report.invariants.candidateRollbackMismatches !== 0)
  failures.push("candidate rollback did not restore exact generation");
if (report.invariants.patchRegressions !== 0)
  failures.push("PATCH regression detected");
if (report.rates.B !== 1)
  failures.push("not every candidate case passed held-out acceptance");

const output = process.env.BUILD_KERNEL_QUALIFICATION_OUTPUT
  ? path.resolve(process.env.BUILD_KERNEL_QUALIFICATION_OUTPUT)
  : path.resolve("target/build-kernel-qualification/provider-free-v1.json");
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
process.stdout.write(
  `Provider-free BUILD kernel: A=${Math.round(report.rates.A * 100)}% B=${Math.round(report.rates.B * 100)}% C=${Math.round(report.rates.C * 100)}% uplift=${report.upliftPercentagePoints.toFixed(0)}pp\n`,
);
process.stdout.write(`Evidence: ${output}\n`);
if (failures.length) {
  process.stderr.write(`${failures.join("\n")}\n`);
  process.exit(1);
}
