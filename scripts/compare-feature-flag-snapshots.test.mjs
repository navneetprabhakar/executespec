import assert from "node:assert/strict";
import test from "node:test";

import { compareSnapshots } from "./compare-feature-flag-snapshots.mjs";

const flag = (key, value, valueType = "BOOLEAN", scope = "PLATFORM") => ({
  key,
  value,
  valueType,
  scope,
});

test("matching local and dev snapshots pass without exceptions", () => {
  const snapshot = [
    flag("spec.chat.enabled", "true"),
    flag("ci.autofix.enabled", "false"),
  ];
  assert.deepEqual(compareSnapshots(snapshot, snapshot), {
    matched: true,
    compared: 2,
    mismatches: [],
    allowed: [],
  });
});

test("a user-facing value mismatch fails closed", () => {
  const result = compareSnapshots(
    [flag("ci.autofix.enabled", "false")],
    [flag("ci.autofix.enabled", "true")],
  );
  assert.equal(result.matched, false);
  assert.deepEqual(
    result.mismatches.map(({ key, field }) => ({ key, field })),
    [{ key: "ci.autofix.enabled", field: "value" }],
  );
});

test("an infra-only exception must name fields and carry a rationale", () => {
  const result = compareSnapshots(
    [flag("operator.diagnostic-sampling", "local")],
    [flag("operator.diagnostic-sampling", "dev")],
    {
      exceptions: [
        {
          key: "operator.diagnostic-sampling",
          fields: ["value"],
          reason: "Operator-only diagnostic sampling differs by environment.",
        },
      ],
    },
  );
  assert.equal(result.matched, true);
  assert.equal(result.allowed.length, 1);
});

test("release-critical local/dev gates can never be hidden by the allowlist", () => {
  assert.throws(
    () =>
      compareSnapshots(
        [flag("requirement.ir.gate.mode", "LEGACY", "STRING")],
        [flag("requirement.ir.gate.mode", "ENFORCED", "STRING")],
        {
          exceptions: [
            {
              key: "requirement.ir.gate.mode",
              fields: ["value"],
              reason:
                "This difference should be rejected despite the rationale.",
            },
          ],
        },
      ),
    /cannot be allowlisted/,
  );
});

test("missing flags and type or scope drift are compared as contract differences", () => {
  const result = compareSnapshots(
    [flag("alpha.enabled", "true", "BOOLEAN", "PLATFORM")],
    [
      flag("alpha.enabled", "true", "STRING", "GLOBAL"),
      flag("beta.enabled", "false"),
    ],
  );
  assert.deepEqual(
    result.mismatches.map(({ key, field }) => `${key}:${field}`),
    ["alpha.enabled:valueType", "alpha.enabled:scope", "beta.enabled:presence"],
  );
  assert.deepEqual(result.mismatches.at(-1), {
    key: "beta.enabled",
    field: "presence",
    local: false,
    dev: true,
  });
});
