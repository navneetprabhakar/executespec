import assert from "node:assert/strict";
import test from "node:test";

import {
  compareControlPlaneGraphs,
  validateReview,
} from "./control-plane-graph-diff.mjs";

const node = (id, sourceFile, label = id) => ({
  id,
  label,
  source_file: sourceFile,
  file_type: "code",
});
const edge = (source, target, relation, sourceFile) => ({
  source,
  target,
  relation,
  confidence: "EXTRACTED",
  source_file: sourceFile,
});

const hashes = {
  baseGraphSha256: "a".repeat(64),
  candidateGraphSha256: "b".repeat(64),
};

test("unrelated source changes do not require a control-plane review", () => {
  const base = { nodes: [node("ui", "frontend/src/App.tsx")], edges: [] };
  const candidate = {
    nodes: [node("ui", "frontend/src/App.tsx"), node("ui2", "frontend/src/New.tsx")],
    edges: [edge("ui", "ui2", "calls", "frontend/src/App.tsx")],
  };

  const diff = compareControlPlaneGraphs(base, candidate, hashes);

  assert.equal(diff.reviewRequired, false);
  assert.deepEqual(validateReview(diff, null), {
    accepted: true,
    reason: "NO_CONTROL_PLANE_CHANGE",
  });
});

test("a generation authority edge change requires exact review", () => {
  const path =
    "src/main/java/com/navneet/executespec/workspacegeneration/WorkspaceGenerationLifecycle.java";
  const base = { nodes: [node("lifecycle", path), node("store", path)], edges: [] };
  const candidate = {
    nodes: base.nodes,
    edges: [edge("lifecycle", "store", "calls", path)],
  };

  const diff = compareControlPlaneGraphs(base, candidate, hashes);

  assert.equal(diff.reviewRequired, true);
  assert.equal(diff.changedEdgeCount, 1);
  assert.ok(diff.impactedAreas.includes("GENERATION"));
  assert.deepEqual(validateReview(diff, null), {
    accepted: false,
    reason: "REVIEW_MISSING",
  });

  const review = approvedReview(diff);
  assert.deepEqual(validateReview(diff, review), {
    accepted: true,
    reason: "EXACT_REVIEW_APPROVED",
  });
});

test("a removed SQL trigger edge is schema-sensitive", () => {
  const path = "src/main/resources/db/migration/V399__persist_generation_operation_intents.sql";
  const base = {
    nodes: [node("migration", path), node("trigger", path, "build_run_terminal_fence")],
    edges: [edge("migration", "trigger", "triggers", path)],
  };
  const candidate = { nodes: [node("migration", path)], edges: [] };

  const diff = compareControlPlaneGraphs(base, candidate, hashes);

  assert.equal(diff.reviewRequired, true);
  assert.equal(diff.changedNodeCount, 1);
  assert.equal(diff.changedEdgeCount, 1);
  assert.ok(diff.impactedAreas.includes("SCHEMA"));
});

test("stale identity and incomplete area coverage fail closed", () => {
  const path = "src/main/java/com/navneet/executespec/sandbox/RunSandboxStore.java";
  const diff = compareControlPlaneGraphs(
    { nodes: [], edges: [] },
    { nodes: [node("sandbox", path)], edges: [] },
    hashes,
  );
  const stale = { ...approvedReview(diff), diffSha256: "c".repeat(64) };
  assert.equal(validateReview(diff, stale).reason, "REVIEW_IDENTITY_MISMATCH");

  const incomplete = { ...approvedReview(diff), impactedAreas: [] };
  assert.equal(
    validateReview(diff, incomplete).reason,
    "REVIEW_AREA_COVERAGE_INCOMPLETE",
  );
});

test("Graphify edge schema cannot silently degrade to an empty graph", () => {
  assert.throws(
    () => compareControlPlaneGraphs({ nodes: [] }, { nodes: [], edges: [] }, hashes),
    /GRAPH_EDGE_COLLECTION_MISSING/,
  );
  assert.throws(
    () =>
      compareControlPlaneGraphs(
        { nodes: [], edges: [], links: [edge("a", "b", "calls", "x")] },
        { nodes: [], edges: [] },
        hashes,
      ),
    /GRAPH_EDGE_COLLECTIONS_CONFLICT/,
  );
});

function approvedReview(diff) {
  return {
    schemaVersion: 1,
    baseGraphSha256: diff.baseGraphSha256,
    candidateGraphSha256: diff.candidateGraphSha256,
    diffSha256: diff.diffSha256,
    decision: "APPROVED",
    reviewer: "independent-reviewer",
    reviewedAt: "2026-08-12T00:00:00Z",
    impactedAreas: diff.impactedAreas,
    rationale:
      "Reviewed every changed control-plane node and edge against its owning invariant.",
  };
}
