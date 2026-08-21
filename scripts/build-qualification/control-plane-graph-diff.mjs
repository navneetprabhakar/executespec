#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const CONTROL_PATH =
  /(?:^|\/)(?:\.github\/workflows\/deploy\.yml|scripts\/build-qualification\/|src\/main\/(?:java\/com\/navneet\/executespec\/(?:workspacegeneration|sandbox|sandboxroute|integration|harness|deployment|state|tool|recovery|report|config\/featureflag)\/|resources\/db\/migration\/V(?:37[4-9]|38\d|39\d|[4-9]\d\d)[^/]*\.sql$))/;

const AREA_RULES = [
  ["SCHEMA", /\/db\/migration\/|\.sql$/],
  ["SANDBOX", /\/sandbox(?:route)?\/|sandbox/i],
  [
    "PUBLICATION",
    /\/integration\/scm\/|publication|push|repository.provision/i,
  ],
  ["GENERATION", /\/workspacegeneration\/|generation|acceptance|rollback/i],
  ["AUTHORITY", /authority|lease|fence|receipt|transition|protocol|state/i],
  [
    "DEPLOYMENT",
    /\/deployment\/|\.github\/workflows\/deploy\.yml|scripts\/build-qualification\//,
  ],
];

const hash = (value) => createHash("sha256").update(value).digest("hex");

const nodePath = (node) => node?.source_file ?? "";

const nodeKey = (node, normalizeEndpoint = (value) => value) =>
  [
    normalizeEndpoint(node.id ?? ""),
    node.label ?? "",
    nodePath(node),
    node.file_type ?? "",
  ].join("\u001f");

const edgeKey = (edge, normalizeEndpoint = (value) => value) =>
  [
    normalizeEndpoint(edge.source ?? ""),
    normalizeEndpoint(edge.target ?? ""),
    edge.relation ?? "",
    edge.confidence ?? "",
    edge.source_file ?? "",
  ].join("\u001f");

const graphifyPathId = (value) =>
  value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");

const graphEndpointNormalization = (graph) => {
  const prefixSources = new Map();
  for (const edge of graphEdges(graph)) {
    const sourceFileId = graphifyPathId(edge.source_file ?? "");
    const source = edge.source ?? "";
    if (
      sourceFileId &&
      source !== sourceFileId &&
      source.endsWith(sourceFileId)
    ) {
      const prefix = source.slice(0, -sourceFileId.length);
      if (prefix.endsWith("_")) {
        const sources = prefixSources.get(prefix) ?? new Set();
        sources.add(edge.source_file);
        prefixSources.set(prefix, sources);
      }
    }
  }
  const prefixes = [...prefixSources.entries()]
    .filter(([, sources]) => sources.size >= 2)
    .map(([prefix]) => prefix)
    .sort((left, right) => right.length - left.length);
  return {
    prefixCount: prefixes.length,
    normalizeEndpoint: (endpoint) => {
      const prefix = prefixes.find((candidate) =>
        endpoint.startsWith(candidate),
      );
      return prefix ? endpoint.slice(prefix.length) : endpoint;
    },
  };
};

const graphEdges = (graph) => {
  const hasEdges = Array.isArray(graph?.edges);
  const hasLinks = Array.isArray(graph?.links);
  if (hasEdges && hasLinks) {
    const edges = graph.edges.map((edge) => edgeKey(edge)).sort();
    const links = graph.links.map((edge) => edgeKey(edge)).sort();
    if (JSON.stringify(edges) !== JSON.stringify(links)) {
      throw new Error("GRAPH_EDGE_COLLECTIONS_CONFLICT");
    }
    return graph.edges;
  }
  if (hasEdges) return graph.edges;
  if (hasLinks) return graph.links;
  throw new Error("GRAPH_EDGE_COLLECTION_MISSING");
};

const semanticGraphHash = (graph) =>
  (() => {
    const { normalizeEndpoint } = graphEndpointNormalization(graph);
    return hash(
      JSON.stringify({
        nodes: (graph.nodes ?? [])
          .map((node) => nodeKey(node, normalizeEndpoint))
          .sort(),
        edges: graphEdges(graph)
          .map((edge) => edgeKey(edge, normalizeEndpoint))
          .sort(),
      }),
    );
  })();

const controlGraphHash = (nodes, edges) =>
  hash(
    JSON.stringify({
      nodes: [...nodes].sort(),
      edges: [...edges].sort(),
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
  const baseNormalization = graphEndpointNormalization(base);
  const candidateNormalization = graphEndpointNormalization(candidate);
  const normalizeBaseEndpoint = baseNormalization.normalizeEndpoint;
  const normalizeCandidateEndpoint = candidateNormalization.normalizeEndpoint;
  const baseNodes = new Map((base.nodes ?? []).map((node) => [node.id, node]));
  const candidateNodes = new Map(
    (candidate.nodes ?? []).map((node) => [node.id, node]),
  );
  const controlIds = new Set();
  for (const node of [...baseNodes.values(), ...candidateNodes.values()]) {
    if (CONTROL_PATH.test(nodePath(node))) controlIds.add(node.id);
  }

  const controlNodeKeys = (nodes, normalizeEndpoint) =>
    new Set(
      [...nodes.values()]
        .filter((node) => controlIds.has(node.id))
        .map((node) => nodeKey(node, normalizeEndpoint)),
    );
  const controlEdgeKeys = (graph, normalizeEndpoint) =>
    new Set(
      graphEdges(graph)
        .filter(
          (edge) =>
            controlIds.has(edge.source) ||
            controlIds.has(edge.target) ||
            CONTROL_PATH.test(edge.source_file ?? ""),
        )
        .map((edge) => edgeKey(edge, normalizeEndpoint)),
    );

  const baseControlNodes = controlNodeKeys(baseNodes, normalizeBaseEndpoint);
  const candidateControlNodes = controlNodeKeys(
    candidateNodes,
    normalizeCandidateEndpoint,
  );
  const baseControlEdges = controlEdgeKeys(base, normalizeBaseEndpoint);
  const candidateControlEdges = controlEdgeKeys(
    candidate,
    normalizeCandidateEndpoint,
  );
  const addedNodes = sortedDifference(candidateControlNodes, baseControlNodes);
  const removedNodes = sortedDifference(
    baseControlNodes,
    candidateControlNodes,
  );
  const addedEdges = sortedDifference(candidateControlEdges, baseControlEdges);
  const removedEdges = sortedDifference(
    baseControlEdges,
    candidateControlEdges,
  );
  const changedValues = [
    ...addedNodes,
    ...removedNodes,
    ...addedEdges,
    ...removedEdges,
  ];
  const canonical = JSON.stringify({
    addedNodes,
    removedNodes,
    addedEdges,
    removedEdges,
  });

  return {
    schemaVersion: 3,
    baseControlGraphSha256: controlGraphHash(
      baseControlNodes,
      baseControlEdges,
    ),
    candidateControlGraphSha256: controlGraphHash(
      candidateControlNodes,
      candidateControlEdges,
    ),
    baseFullGraphSha256: rawHashes.baseFullGraphSha256 ?? null,
    candidateFullGraphSha256: rawHashes.candidateFullGraphSha256 ?? null,
    baseArtifactSha256: rawHashes.baseArtifactSha256 ?? null,
    candidateArtifactSha256: rawHashes.candidateArtifactSha256 ?? null,
    baseExtractionPrefixCount: baseNormalization.prefixCount,
    candidateExtractionPrefixCount: candidateNormalization.prefixCount,
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
    controlPlaneChanged: changedValues.length > 0,
    diffSha256: hash(canonical),
  };
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
    baseFullGraphSha256: semanticGraphHash(base),
    candidateFullGraphSha256: semanticGraphHash(candidate),
    baseArtifactSha256: hash(baseRaw),
    candidateArtifactSha256: hash(candidateRaw),
  });
  await writeFile(args.output, `${JSON.stringify(diff, null, 2)}\n`, {
    flag: "wx",
  });
  process.stdout.write(
    `control-plane graph evidence recorded: ${diff.changedNodeCount} node changes, ${diff.changedEdgeCount} edge changes\n`,
  );
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
