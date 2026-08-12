#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const CONTROL_PATH = /(?:^|\/)(?:\.github\/workflows\/deploy\.yml|scripts\/build-qualification\/|src\/main\/(?:java\/com\/navneet\/executespec\/(?:workspacegeneration|sandbox|sandboxroute|integration|harness|deployment|state|tool|recovery|report|config\/featureflag)\/|resources\/db\/migration\/V(?:37[4-9]|38\d|39\d|[4-9]\d\d)[^/]*\.sql$))/;

const AREA_RULES = [
  ["SCHEMA", /\/db\/migration\/|\.sql$/],
  ["SANDBOX", /\/sandbox(?:route)?\/|sandbox/i],
  ["PUBLICATION", /\/integration\/scm\/|publication|push|repository.provision/i],
  ["GENERATION", /\/workspacegeneration\/|generation|acceptance|rollback/i],
  ["AUTHORITY", /authority|lease|fence|receipt|transition|protocol|state/i],
  ["DEPLOYMENT", /\/deployment\/|\.github\/workflows\/deploy\.yml/],
];

const hash = (value) =>
  createHash("sha256").update(value).digest("hex");

const nodePath = (node) => node?.source_file ?? "";

const nodeKey = (node) =>
  [node.id ?? "", node.label ?? "", nodePath(node), node.file_type ?? ""].join("\u001f");

const edgeKey = (edge) =>
  [
    edge.source ?? "",
    edge.target ?? "",
    edge.relation ?? "",
    edge.confidence ?? "",
    edge.source_file ?? "",
  ].join("\u001f");

const semanticGraphHash = (graph) =>
  hash(
    JSON.stringify({
      nodes: (graph.nodes ?? []).map(nodeKey).sort(),
      edges: (graph.links ?? []).map(edgeKey).sort(),
    }),
  );

const sortedDifference = (left, right) =>
  [...left].filter((value) => !right.has(value)).sort();

const areasFor = (values) => {
  const areas = new Set();
  for (const value of values) {
    for (const [area, pattern] of AREA_RULES) {
      if (pattern.test(value)) areas.add(area);
    }
  }
  return [...areas].sort();
};

export function compareControlPlaneGraphs(base, candidate, rawHashes = {}) {
  const baseNodes = new Map((base.nodes ?? []).map((node) => [node.id, node]));
  const candidateNodes = new Map(
    (candidate.nodes ?? []).map((node) => [node.id, node]),
  );
  const controlIds = new Set();
  for (const node of [...baseNodes.values(), ...candidateNodes.values()]) {
    if (CONTROL_PATH.test(nodePath(node))) controlIds.add(node.id);
  }

  const controlNodeKeys = (nodes) =>
    new Set(
      [...nodes.values()]
        .filter((node) => controlIds.has(node.id))
        .map(nodeKey),
    );
  const controlEdgeKeys = (graph) =>
    new Set(
      (graph.links ?? [])
        .filter(
          (edge) =>
            controlIds.has(edge.source) ||
            controlIds.has(edge.target) ||
            CONTROL_PATH.test(edge.source_file ?? ""),
        )
        .map(edgeKey),
    );

  const baseControlNodes = controlNodeKeys(baseNodes);
  const candidateControlNodes = controlNodeKeys(candidateNodes);
  const baseControlEdges = controlEdgeKeys(base);
  const candidateControlEdges = controlEdgeKeys(candidate);
  const addedNodes = sortedDifference(candidateControlNodes, baseControlNodes);
  const removedNodes = sortedDifference(baseControlNodes, candidateControlNodes);
  const addedEdges = sortedDifference(candidateControlEdges, baseControlEdges);
  const removedEdges = sortedDifference(baseControlEdges, candidateControlEdges);
  const changedValues = [...addedNodes, ...removedNodes, ...addedEdges, ...removedEdges];
  const canonical = JSON.stringify({ addedNodes, removedNodes, addedEdges, removedEdges });

  return {
    schemaVersion: 1,
    baseGraphSha256: rawHashes.baseGraphSha256 ?? null,
    candidateGraphSha256: rawHashes.candidateGraphSha256 ?? null,
    baseArtifactSha256: rawHashes.baseArtifactSha256 ?? null,
    candidateArtifactSha256: rawHashes.candidateArtifactSha256 ?? null,
    baseControlNodeCount: baseControlNodes.size,
    candidateControlNodeCount: candidateControlNodes.size,
    baseControlEdgeCount: baseControlEdges.size,
    candidateControlEdgeCount: candidateControlEdges.size,
    addedNodes,
    removedNodes,
    addedEdges,
    removedEdges,
    changedNodeCount: addedNodes.length + removedNodes.length,
    changedEdgeCount: addedEdges.length + removedEdges.length,
    impactedAreas: areasFor(changedValues),
    reviewRequired: changedValues.length > 0,
    diffSha256: hash(canonical),
  };
}

export function validateReview(diff, review) {
  if (!diff.reviewRequired) return { accepted: true, reason: "NO_CONTROL_PLANE_CHANGE" };
  if (!review || typeof review !== "object") {
    return { accepted: false, reason: "REVIEW_MISSING" };
  }
  const exact =
    review.schemaVersion === 1 &&
    review.baseGraphSha256 === diff.baseGraphSha256 &&
    review.candidateGraphSha256 === diff.candidateGraphSha256 &&
    review.diffSha256 === diff.diffSha256;
  if (!exact) return { accepted: false, reason: "REVIEW_IDENTITY_MISMATCH" };
  if (review.decision !== "APPROVED") {
    return { accepted: false, reason: "REVIEW_NOT_APPROVED" };
  }
  if (typeof review.reviewer !== "string" || review.reviewer.trim().length < 3) {
    return { accepted: false, reason: "REVIEWER_MISSING" };
  }
  if (
    typeof review.rationale !== "string" ||
    review.rationale.trim().length < 30
  ) {
    return { accepted: false, reason: "REVIEW_RATIONALE_TOO_SHORT" };
  }
  if (!Number.isFinite(Date.parse(review.reviewedAt ?? ""))) {
    return { accepted: false, reason: "REVIEW_TIMESTAMP_INVALID" };
  }
  const covered = new Set(review.impactedAreas ?? []);
  if (!diff.impactedAreas.every((area) => covered.has(area))) {
    return { accepted: false, reason: "REVIEW_AREA_COVERAGE_INCOMPLETE" };
  }
  return { accepted: true, reason: "EXACT_REVIEW_APPROVED" };
}

function parseArgs(args) {
  const parsed = {};
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    const value = args[index + 1];
    if (!key?.startsWith("--") || !value) throw new Error("INVALID_ARGUMENTS");
    parsed[key.slice(2)] = value;
  }
  for (const required of ["base", "candidate", "output"]) {
    if (!parsed[required]) throw new Error(`MISSING_${required.toUpperCase()}`);
  }
  return parsed;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const [baseRaw, candidateRaw] = await Promise.all([
    readFile(args.base, "utf8"),
    readFile(args.candidate, "utf8"),
  ]);
  const base = JSON.parse(baseRaw);
  const candidate = JSON.parse(candidateRaw);
  const diff = compareControlPlaneGraphs(base, candidate, {
    baseGraphSha256: semanticGraphHash(base),
    candidateGraphSha256: semanticGraphHash(candidate),
    baseArtifactSha256: hash(baseRaw),
    candidateArtifactSha256: hash(candidateRaw),
  });
  let review = null;
  if (args.review) review = JSON.parse(await readFile(args.review, "utf8"));
  const result = validateReview(diff, review);
  const output = { ...diff, review: result };
  await writeFile(args.output, `${JSON.stringify(output, null, 2)}\n`, { flag: "wx" });

  if (diff.reviewRequired && !result.accepted) {
    const template = {
      schemaVersion: 1,
      baseGraphSha256: diff.baseGraphSha256,
      candidateGraphSha256: diff.candidateGraphSha256,
      diffSha256: diff.diffSha256,
      decision: "APPROVED",
      reviewer: "REPLACE_WITH_INDEPENDENT_REVIEWER",
      reviewedAt: "REPLACE_WITH_ISO_8601_TIMESTAMP",
      impactedAreas: diff.impactedAreas,
      rationale: "REPLACE_WITH_AT_LEAST_30_CHARACTERS_OF_SPECIFIC_REVIEW_REASONING",
    };
    await writeFile(
      `${args.output}.review-template.json`,
      `${JSON.stringify(template, null, 2)}\n`,
      { flag: "wx" },
    );
    process.stderr.write(`control-plane graph review required: ${result.reason}\n`);
    process.exitCode = 3;
  } else {
    process.stdout.write(`control-plane graph diff accepted: ${result.reason}\n`);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
