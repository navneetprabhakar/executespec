#!/usr/bin/env node

import fs from "node:fs";
import crypto from "node:crypto";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const backend = path.resolve(scriptDir, "..");
const cli = path.resolve(backend, "../executespec-cli-1.0");
const docs = path.resolve(backend, "../executespec-docs-2.0");
const output = path.join(backend, "docs", "plans", "evidence", "executespec-2.0-inventory.json");
const check = process.argv.includes("--check");

function git(repo, ...args) {
  return execFileSync("git", ["-C", repo, ...args], { encoding: "utf8" }).trim();
}

function tracked(repo) {
  return git(repo, "ls-files").split("\n").filter(Boolean).sort();
}

function sourceFiles(repo) {
  return git(repo, "ls-files", "--cached", "--others", "--exclude-standard")
    .split("\n")
    .filter(Boolean)
    .filter((file) => !file.endsWith("docs/plans/evidence/executespec-2.0-inventory.json"))
    .sort();
}

function fingerprint(repo) {
  const hash = crypto.createHash("sha256");
  for (const file of sourceFiles(repo)) {
    hash.update(file);
    hash.update("\0");
    hash.update(fs.readFileSync(path.join(repo, file)));
    hash.update("\0");
  }
  return hash.digest("hex");
}

function text(repo, relative) {
  return fs.readFileSync(path.join(repo, relative), "utf8");
}

function lines(repo, files) {
  return files.reduce((sum, file) => {
    const value = text(repo, file);
    return sum + (value.match(/\n/g)?.length ?? 0) + (value.length > 0 && !value.endsWith("\n") ? 1 : 0);
  }, 0);
}

function matches(repo, files, expression) {
  let count = 0;
  for (const file of files) count += text(repo, file).match(expression)?.length ?? 0;
  return count;
}

function uniqueMatches(repo, files, expression, group = 1) {
  const values = new Set();
  for (const file of files) {
    for (const match of text(repo, file).matchAll(expression)) values.add(match[group]);
  }
  return [...values].sort();
}

function repository(repo) {
  return {
    name: path.basename(repo),
    sha: git(repo, "rev-parse", "HEAD"),
    branch: git(repo, "branch", "--show-current"),
    trackedFiles: tracked(repo).length,
    worktreeFingerprint: fingerprint(repo),
  };
}

// Inventory the actual clean integration worktrees, including intentional untracked release
// artifacts (V256, manifests, and the M0 scripts). The source fingerprint uses the same set.
const backendFiles = sourceFiles(backend);
const cliFiles = sourceFiles(cli);
const docsFiles = sourceFiles(docs);
const mainJava = backendFiles.filter((file) => file.startsWith("src/main/java/") && file.endsWith(".java"));
const testJava = backendFiles.filter((file) => file.startsWith("src/test/java/") && file.endsWith(".java"));
const promptFiles = backendFiles.filter((file) => file.startsWith("src/main/resources/prompts/"));
const frontendSources = backendFiles.filter(
  (file) => file.startsWith("frontend/src/") && /\.(?:ts|tsx)$/.test(file),
);
const frontendTests = frontendSources.filter((file) => /(?:\.test\.|\/test\/|\/tests\/)/.test(file));
const cliSources = cliFiles.filter((file) => /^(?:cli|core|vscode)\/src\/.*\.(?:ts|tsx)$/.test(file));
const cliTests = cliFiles.filter((file) => /^(?:cli|core|vscode)\/test\/.*\.(?:mjs|ts|tsx)$/.test(file));
const config = text(backend, "src/main/resources/application.yml");
const providerEnum = text(backend, "src/main/java/com/navneet/executespec/router/LlmProvider.java");
const app = text(backend, "frontend/src/App.tsx");

const migrationFilesAll = backendFiles.filter(
  (file) => /^src\/main\/resources\/db\/migration\/V\d+(?:_\d+)*__.*\.sql$/.test(file),
);
const migrationVersions = migrationFilesAll.map((file) => file.match(/\/V(\d+(?:_\d+)*)__/)?.[1]);
const migrationHead = migrationVersions
  .map((value) => value.split("_").map(Number))
  .sort((a, b) => {
    for (let i = 0; i < Math.max(a.length, b.length); i += 1) {
      const difference = (a[i] ?? 0) - (b[i] ?? 0);
      if (difference !== 0) return difference;
    }
    return 0;
  })
  .at(-1)
  .join("_");
const providers = [...providerEnum.matchAll(/^\s*([A-Z][A-Z_]+)\s*\(/gm)]
  .map((match) => match[1].toLowerCase())
  .sort();

function yamlMapKeys(document, section, child) {
  const result = [];
  const rows = document.split("\n");
  let inSection = false;
  let inChild = false;
  for (const row of rows) {
    if (/^[^ #]/.test(row)) {
      inSection = row === `${section}:`;
      inChild = false;
      continue;
    }
    if (!inSection) continue;
    if (/^  [^ #][^:]*:\s*(?:#.*)?$/.test(row)) {
      inChild = row.trim().replace(/:.*$/, "") === child;
      continue;
    }
    if (inChild) {
      const match = row.match(/^    (?:\[([^\]]+)\]|([^ #][^:]*)):\s*(?:#.*)?$/);
      if (match) result.push(match[1] ?? match[2]);
      else if (/^ {0,3}[^ ]/.test(row)) break;
    }
  }
  return [...new Set(result)].sort();
}

const modelIds = yamlMapKeys(config, "model-matrix", "models");
const directFlags = uniqueMatches(
  backend,
  mainJava,
  /(?:isEnabled|isDisabled)\(\s*"([a-zA-Z0-9_.-]+)"/g,
);
const constantFlags = uniqueMatches(
  backend,
  mainJava,
  /(?:FLAG|FEATURE)[A-Z0-9_]*\s*=\s*"([a-zA-Z0-9_.-]+)"/g,
);
const featureFlagLiterals = [...new Set([...directFlags, ...constantFlags])].sort();

const inventory = {
  schemaVersion: 1,
  repositories: {
    backend: repository(backend),
    cli: repository(cli),
    docs: repository(docs),
  },
  backend: {
    mainJava: { files: mainJava.length, lines: lines(backend, mainJava) },
    testJavaFiles: testJava.length,
    controllers: mainJava.filter((file) => file.endsWith("Controller.java")).length,
    mappingAnnotations: matches(backend, mainJava, /@(Get|Post|Put|Patch|Delete|Request)Mapping\b/g),
    prompts: {
      count: promptFiles.length,
      markdown: promptFiles.filter((file) => file.endsWith(".md")).length,
      text: promptFiles.filter((file) => file.endsWith(".txt")).length,
      json: promptFiles.filter((file) => file.endsWith(".json")).length,
      files: promptFiles,
    },
    migrations: { count: migrationFilesAll.length, head: `V${migrationHead}` },
    providers,
    modelIds,
    featureFlagLiterals,
    web: {
      sourceFiles: frontendSources.length,
      lines: lines(backend, frontendSources),
      testFiles: frontendTests.length,
      routePaths: [...app.matchAll(/path=["'{`]([^"'`}]+)/g)].map((match) => match[1]).sort(),
    },
    benchmarkAdapters: ["benchmark", "programbench", "swebench", "terminalbench"].filter((name) =>
      mainJava.some((file) => file.includes(`/executespec/${name}/`)),
    ),
  },
  cli: {
    sourceFiles: cliSources.length,
    lines: lines(cli, cliSources),
    testFiles: cliTests.length,
    rendererRoots: [
      "cli/src/opentuiApp.ts",
      "cli/src/app-tui",
      "cli/src/tui-terminal",
      "cli/src/tui",
      "cli/src/repl.ts",
    ],
  },
  docs: {
    trackedFiles: docsFiles.length,
    markdownFiles: docsFiles.filter((file) => file.endsWith(".md")).length,
    outputFiles: docsFiles.filter((file) => file.startsWith("output/")).length,
  },
};

const rendered = `${JSON.stringify(inventory, null, 2)}\n`;
if (check) {
  let current = null;
  try { current = JSON.parse(fs.readFileSync(output, "utf8")); } catch { current = null; }
  // Commit SHA changes are expected when the inventory itself is committed. Compare the
  // reproducible source/facts payload while allowing the evidence artifact's per-repo SHA fields
  // to advance with the commit that contains it.
  const comparable = (value) => {
    if (Array.isArray(value)) return value.map(comparable);
    if (value && typeof value === "object") {
      return Object.fromEntries(Object.entries(value).filter(([key]) => key !== "sha").map(([key, item]) => [key, comparable(item)]));
    }
    return value;
  };
  if (!current || JSON.stringify(comparable(current)) !== JSON.stringify(comparable(JSON.parse(rendered)))) {
    process.stderr.write(`Inventory is stale. Regenerate with: node ${path.relative(backend, fileURLToPath(import.meta.url))}\n`);
    process.exit(1);
  }
} else {
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, rendered);
  process.stdout.write(`${output}\n`);
}
