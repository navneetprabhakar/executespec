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
  baseFullGraphSha256: "a".repeat(64),
  candidateFullGraphSha256: "b".repeat(64),
};

test("unrelated source changes do not require a control-plane review", () => {
  const base = { nodes: [node("ui", "frontend/src/App.tsx")], edges: [] };
  const candidate = {
    nodes: [
      node("ui", "frontend/src/App.tsx"),
      node("ui2", "frontend/src/New.tsx"),
    ],
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
  const base = {
    nodes: [node("lifecycle", path), node("store", path)],
    edges: [],
  };
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
  const path =
    "src/main/resources/db/migration/V399__persist_generation_operation_intents.sql";
  const base = {
    nodes: [
      node("migration", path),
      node("trigger", path, "build_run_terminal_fence"),
    ],
    edges: [edge("migration", "trigger", "triggers", path)],
  };
  const candidate = { nodes: [node("migration", path)], edges: [] };

  const diff = compareControlPlaneGraphs(base, candidate, hashes);

  assert.equal(diff.reviewRequired, true);
  assert.equal(diff.changedNodeCount, 1);
  assert.equal(diff.changedEdgeCount, 1);
  assert.ok(diff.impactedAreas.includes("SCHEMA"));
});

test("qualification script changes are deployment-sensitive", () => {
  const path = "scripts/build-qualification/control-plane-graph-diff.mjs";
  const diff = compareControlPlaneGraphs(
    { nodes: [], edges: [] },
    { nodes: [node("gate", path)], edges: [] },
    hashes,
  );

  assert.equal(diff.reviewRequired, true);
  assert.ok(diff.impactedAreas.includes("DEPLOYMENT"));
});

test("stale identity and incomplete area coverage fail closed", () => {
  const path =
    "src/main/java/com/navneet/executespec/sandbox/RunSandboxStore.java";
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
    () =>
      compareControlPlaneGraphs(
        { nodes: [] },
        { nodes: [], edges: [] },
        hashes,
      ),
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

test("absolute extraction roots do not create semantic edge changes", () => {
  const path =
    "src/main/java/com/navneet/executespec/workspacegeneration/WorkspaceGenerationLifecycle.java";
  const pathId =
    "src_main_java_com_navneet_executespec_workspacegeneration_workspacegenerationlifecycle_java";
  const secondPath =
    "src/main/java/com/navneet/executespec/workspacegeneration/WorkspaceGenerationStore.java";
  const secondPathId =
    "src_main_java_com_navneet_executespec_workspacegeneration_workspacegenerationstore_java";
  const nodes = [node("lifecycle", path), node("store", secondPath)];
  const extracted = (root) => ({
    nodes,
    edges: [
      edge(`${root}_${pathId}`, `${root}_store`, "calls", path),
      edge(`${root}_${secondPathId}`, `${root}_lifecycle`, "calls", secondPath),
    ],
  });

  const diff = compareControlPlaneGraphs(
    extracted("private_tmp_deployed_base"),
    extracted("home_runner_work_candidate"),
    hashes,
  );

  assert.equal(diff.reviewRequired, false);
  assert.equal(diff.changedNodeCount, 0);
  assert.equal(diff.changedEdgeCount, 0);
  assert.equal(diff.baseExtractionPrefixCount, 1);
  assert.equal(diff.candidateExtractionPrefixCount, 1);
  assert.equal(diff.baseControlGraphSha256, diff.candidateControlGraphSha256);
});

test("review identity ignores nondeterministic nodes outside the control graph", () => {
  const path =
    "src/main/java/com/navneet/executespec/workspacegeneration/WorkspaceGenerationLifecycle.java";
  const controlNodes = [node("lifecycle", path), node("store", path)];
  const controlEdges = [edge("lifecycle", "store", "calls", path)];
  const first = compareControlPlaneGraphs(
    { nodes: controlNodes, edges: [] },
    {
      nodes: [...controlNodes, node("random-a", "frontend/src/A.tsx")],
      edges: [
        ...controlEdges,
        edge("random-a", "random-b", "calls", "frontend/src/A.tsx"),
      ],
    },
    {
      baseFullGraphSha256: "a".repeat(64),
      candidateFullGraphSha256: "b".repeat(64),
    },
  );
  const second = compareControlPlaneGraphs(
    { nodes: controlNodes, edges: [] },
    {
      nodes: [...controlNodes, node("random-c", "frontend/src/C.tsx")],
      edges: [
        ...controlEdges,
        edge("random-c", "random-d", "calls", "frontend/src/C.tsx"),
      ],
    },
    {
      baseFullGraphSha256: "c".repeat(64),
      candidateFullGraphSha256: "d".repeat(64),
    },
  );

  assert.notEqual(
    first.candidateFullGraphSha256,
    second.candidateFullGraphSha256,
  );
  assert.equal(
    first.candidateControlGraphSha256,
    second.candidateControlGraphSha256,
  );
  assert.equal(first.diffSha256, second.diffSha256);
  assert.deepEqual(validateReview(second, approvedReview(first)), {
    accepted: true,
    reason: "EXACT_REVIEW_APPROVED",
  });
});

function approvedReview(diff) {
  return {
    schemaVersion: 2,
    baseControlGraphSha256: diff.baseControlGraphSha256,
    candidateControlGraphSha256: diff.candidateControlGraphSha256,
    diffSha256: diff.diffSha256,
    decision: "APPROVED",
    reviewer: "independent-reviewer",
    reviewedAt: "2026-08-12T00:00:00Z",
    impactedAreas: diff.impactedAreas,
    rationale:
      "Reviewed every changed control-plane node and edge against its owning invariant.",
  };
}
