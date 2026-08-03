import {createHash} from 'node:crypto';
import {execFile} from 'node:child_process';
import {mkdir, readFile, rename, writeFile} from 'node:fs/promises';
import path from 'node:path';
import {promisify} from 'node:util';

const execFileAsync = promisify(execFile);
const SAFE_SUBPROCESS_ENV_KEYS = new Set([
  'APPDATA',
  'COMSPEC',
  'GIT_CONFIG_NOSYSTEM',
  'GIT_TERMINAL_PROMPT',
  'GH_CONFIG_DIR',
  'HOME',
  'LANG',
  'LC_ALL',
  'LOCALAPPDATA',
  'PATH',
  'PATHEXT',
  'SHELL',
  'SYSTEMROOT',
  'TEMP',
  'TMP',
  'TMPDIR',
  'XDG_CONFIG_HOME',
  'XDG_DATA_HOME',
]);

export const SCHEMA_VERSION = 1;
const TOP_LEVEL_KEYS = new Set([
  'schemaVersion', 'campaignId', 'state', 'createdAt', 'updatedAt', 'invalidation',
  'deployment', 'fixture', 'project', 'route', 'runRequest', 'approval', 'clients',
  'frozenTupleDigest', 'heroes', 'postMerge', 'negatives', 'cleanup', 'events',
]);
export const ALLOWED_ROUTES = Object.freeze({
  DEEPSEEK: new Set(['deepseek-v4-pro-native', 'deepseek-v4-flash-native']),
  FIREWORKS: new Set(['glm-5.2-fw']),
});

export const STATE_TRANSITIONS = Object.freeze({
  NEW: ['PREFLIGHTED', 'INVALIDATED'],
  PREFLIGHTED: ['FIXTURE_CREATED', 'INVALIDATED'],
  FIXTURE_CREATED: ['PROJECT_LINKED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  PROJECT_LINKED: ['BASELINE_V1_READY', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  BASELINE_V1_READY: ['SOURCE_REVISION_PUSHED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  SOURCE_REVISION_PUSHED: ['BASELINE_V2_READY', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  BASELINE_V2_READY: ['FROZEN', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  FROZEN: ['HERO_1_RUNNING', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  HERO_1_RUNNING: ['HERO_1_VERIFIED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  HERO_1_VERIFIED: ['HERO_2_RUNNING', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  HERO_2_RUNNING: ['HERO_2_VERIFIED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  HERO_2_VERIFIED: ['HERO_3_RUNNING', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  HERO_3_RUNNING: ['HERO_3_VERIFIED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  HERO_3_VERIFIED: ['HERO_PR_MERGED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  HERO_PR_MERGED: ['MERGED_SHA_REFRESHED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  MERGED_SHA_REFRESHED: ['CONTINUATION_VERIFIED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  CONTINUATION_VERIFIED: ['CI_FAILURE_CREATED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  CI_FAILURE_CREATED: ['CI_FIX_VERIFIED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  CI_FIX_VERIFIED: ['DEPLOYMENT_TRACKED', 'DEPLOYMENT_SKIPPED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  DEPLOYMENT_TRACKED: ['NEGATIVES_VERIFIED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  DEPLOYMENT_SKIPPED: ['NEGATIVES_VERIFIED', 'CLEANUP_REQUIRED', 'INVALIDATED'],
  NEGATIVES_VERIFIED: ['CLEANUP_REQUIRED', 'INVALIDATED'],
  CLEANUP_REQUIRED: ['CLEANUP_VERIFIED', 'INVALIDATED'],
  CLEANUP_VERIFIED: ['COMPLETE', 'INVALIDATED'],
  COMPLETE: [],
  INVALIDATED: ['CLEANUP_REQUIRED'],
});

export function redactText(value) {
  return String(value ?? '')
    .replace(/\b(?:ghp|github_pat|es_pat)_[A-Za-z0-9_\-]+\b/g, '[REDACTED]')
    .replace(/\b(?:sk|sk-ant|sk-proj)-[A-Za-z0-9_\-]{12,}\b/g, '[REDACTED]')
    .replace(/\bBearer\s+[^\s,;]+/gi, 'Bearer [REDACTED]')
    .replace(/([?&](?:token|access_token|refresh_token|api_key|key)=)[^&\s]+/gi, '$1[REDACTED]')
    .replace(/("?(?:authorization|password|secret|token|apiKey|api_key)"?\s*[:=]\s*")([^"]+)(")/gi, '$1[REDACTED]$3');
}

export function redactValue(value, key = '') {
  if (/authorization|password|secret|token|api.?key/i.test(key)) return '[REDACTED]';
  if (Array.isArray(value)) return value.map((item) => redactValue(item));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([childKey, child]) => [childKey, redactValue(child, childKey)]));
  }
  return typeof value === 'string' ? redactText(value) : value;
}

export function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

export function assertSha(value, label) {
  if (!/^[0-9a-f]{40}$/i.test(String(value ?? ''))) throw new Error(`${label} must be an exact 40-hex Git SHA`);
}

export function validateRoute(provider, model) {
  const normalizedProvider = String(provider ?? '').toUpperCase();
  const normalizedModel = String(model ?? '').trim();
  const allowed = ALLOWED_ROUTES[normalizedProvider];
  if (!allowed || !allowed.has(normalizedModel)) {
    throw new Error('Paid litmus routes are restricted to native DeepSeek or GLM 5.2 on Fireworks');
  }
  return {provider: normalizedProvider, model: normalizedModel};
}

export function requireConfirmation(args, name, expected = 'YES') {
  if (args[name] !== expected) throw new Error(`Refusing live mutation: --${name}=${expected} is required`);
}

export function parseArgs(argv) {
  const positional = [];
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) {
      positional.push(token);
      continue;
    }
    const equals = token.indexOf('=');
    if (equals >= 0) {
      values[token.slice(2, equals)] = token.slice(equals + 1);
      continue;
    }
    const key = token.slice(2);
    const next = argv[index + 1];
    if (next && !next.startsWith('--')) {
      values[key] = next;
      index += 1;
    } else values[key] = true;
  }
  return {positional, values};
}

export function newManifest(input = {}) {
  const route = validateRoute(input.provider ?? 'DEEPSEEK', input.model ?? 'deepseek-v4-pro-native');
  return {
    schemaVersion: SCHEMA_VERSION,
    campaignId: input.campaignId ?? `litmus-${new Date().toISOString().replace(/[^0-9]/g, '').slice(0, 14)}`,
    state: 'NEW',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    invalidation: null,
    deployment: input.deployment ?? {},
    fixture: input.fixture ?? {},
    project: input.project ?? {},
    route: {...route, routingMode: 'EXACT_LOCK'},
    runRequest: input.runRequest ?? {},
    approval: {mode: 'MANUAL_EXACT_PLAN', fingerprintAlgorithm: 'SHA-256_UTF8_PLAN_JSON'},
    clients: input.clients ?? {},
    frozenTupleDigest: null,
    heroes: [],
    postMerge: {},
    negatives: [],
    cleanup: {},
    events: [],
  };
}

export function validateManifest(manifest) {
  if (!manifest || manifest.schemaVersion !== SCHEMA_VERSION) throw new Error('Unsupported manifest schema');
  const unknownKeys = Object.keys(manifest).filter((key) => !TOP_LEVEL_KEYS.has(key));
  if (unknownKeys.length) throw new Error(`Unknown manifest fields: ${unknownKeys.join(', ')}`);
  if (!(manifest.state in STATE_TRANSITIONS)) throw new Error(`Unknown manifest state: ${manifest.state}`);
  validateRoute(manifest.route?.provider, manifest.route?.model);
  if (manifest.route?.routingMode !== 'EXACT_LOCK') throw new Error('Litmus route must be EXACT_LOCK');
  if (manifest.approval?.mode !== 'MANUAL_EXACT_PLAN'
      || manifest.approval?.fingerprintAlgorithm !== 'SHA-256_UTF8_PLAN_JSON') {
    throw new Error('Litmus approval must use the exact manual-plan fingerprint contract');
  }
  if (!Array.isArray(manifest.heroes) || !Array.isArray(manifest.negatives) || !Array.isArray(manifest.events)) {
    throw new Error('Manifest evidence collections must be arrays');
  }
  if (manifest.state === 'FROZEN' || manifest.frozenTupleDigest) assertFrozenTuple(manifest);
  return manifest;
}

export function transition(manifest, next, evidence = {}) {
  validateManifest(manifest);
  const allowed = STATE_TRANSITIONS[manifest.state] ?? [];
  if (!allowed.includes(next)) throw new Error(`Illegal litmus transition ${manifest.state} -> ${next}`);
  const beforeDigest = manifest.frozenTupleDigest;
  const updated = structuredClone(manifest);
  updated.state = next;
  updated.updatedAt = new Date().toISOString();
  updated.events.push({at: updated.updatedAt, from: manifest.state, to: next, evidence: redactValue(evidence)});
  if (beforeDigest && next !== 'INVALIDATED' && frozenTupleDigest(updated) !== beforeDigest) {
    throw new Error('Frozen tuple changed; invalidate the campaign instead of advancing it');
  }
  return updated;
}

export function frozenTuple(manifest) {
  return {
    deployment: manifest.deployment,
    fixture: manifest.fixture,
    project: manifest.project,
    route: manifest.route,
    runRequest: manifest.runRequest,
    approval: manifest.approval,
    clients: manifest.clients,
  };
}

export function frozenTupleDigest(manifest) {
  return sha256(canonicalJson(frozenTuple(manifest)));
}

export function assertFrozenTuple(manifest) {
  const expected = manifest.frozenTupleDigest;
  if (!expected) throw new Error('Frozen manifest is missing frozenTupleDigest');
  const actual = frozenTupleDigest(manifest);
  if (expected !== actual) throw new Error(`Frozen tuple digest mismatch: expected ${expected}, observed ${actual}`);
  return actual;
}

export function freezeManifest(manifest) {
  if (manifest.state !== 'BASELINE_V2_READY') throw new Error('Manifest can freeze only from BASELINE_V2_READY');
  assertSha(manifest.deployment.expectedSha, 'deployment.expectedSha');
  assertSha(manifest.deployment.runningSha, 'deployment.runningSha');
  if (manifest.deployment.expectedSha !== manifest.deployment.runningSha) throw new Error('Deployed and running SHA differ');
  assertSha(manifest.fixture.baseSha, 'fixture.baseSha');
  if (!manifest.fixture.repoFullName || !manifest.project.projectId || !manifest.project.repoId) {
    throw new Error('Fixture and project identities are required before freeze');
  }
  if (!manifest.runRequest.requirementSha256 || !manifest.runRequest.canonicalJsonSha256) {
    throw new Error('Frozen run request hashes are required');
  }
  const updated = structuredClone(manifest);
  updated.frozenTupleDigest = frozenTupleDigest(updated);
  updated.state = 'FROZEN';
  updated.updatedAt = new Date().toISOString();
  updated.events.push({at: updated.updatedAt, from: manifest.state, to: 'FROZEN', evidence: {frozenTupleDigest: updated.frozenTupleDigest}});
  return updated;
}

export function invalidateManifest(manifest, reason) {
  if (!String(reason ?? '').trim()) throw new Error('Invalidation reason is required');
  const updated = structuredClone(manifest);
  updated.state = 'INVALIDATED';
  updated.updatedAt = new Date().toISOString();
  updated.invalidation = {at: updated.updatedAt, reason: redactText(reason)};
  updated.events.push({at: updated.updatedAt, from: manifest.state, to: 'INVALIDATED', evidence: updated.invalidation});
  return updated;
}

export async function loadManifest(file) {
  return validateManifest(JSON.parse(await readFile(file, 'utf8')));
}

export async function saveManifest(file, manifest) {
  validateManifest(manifest);
  await mkdir(path.dirname(file), {recursive: true, mode: 0o700});
  const temporary = `${file}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(redactValue(manifest), null, 2)}\n`, {mode: 0o600});
  await rename(temporary, file);
}

export async function runCommand(file, args, options = {}) {
  if (!Array.isArray(args) || args.some((arg) => typeof arg !== 'string')) throw new Error('Subprocess arguments must be a string array');
  const sourceEnv = options.env ?? process.env;
  const safeEnv = Object.fromEntries(
    Object.entries(sourceEnv).filter(([key]) => SAFE_SUBPROCESS_ENV_KEYS.has(key.toUpperCase())),
  );
  try {
    const result = await execFileAsync(file, args, {
      cwd: options.cwd,
      env: safeEnv,
      timeout: options.timeoutMs ?? 60_000,
      maxBuffer: options.maxBuffer ?? 4 * 1024 * 1024,
      windowsHide: true,
    });
    return {stdout: redactText(result.stdout), stderr: redactText(result.stderr)};
  } catch (error) {
    const exitCode = typeof error?.code === 'number' ? ` (exit ${error.code})` : '';
    const detail = redactText(error?.stderr || error?.stdout || error?.message || 'command failed');
    throw new Error(`${file} failed${exitCode}: ${detail}`);
  }
}

export async function apiRequest(baseUrl, token, pathname, options = {}) {
  const method = options.method ?? 'GET';
  const response = await fetch(new URL(pathname, baseUrl), {
    method,
    headers: {
      ...(token ? {Authorization: `Bearer ${token}`} : {}),
      ...(options.body === undefined ? {} : {'Content-Type': 'application/json'}),
      ...options.headers,
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(options.timeoutMs ?? 20_000),
  });
  const text = await response.text();
  let body = null;
  if (text) {
    try { body = JSON.parse(text); } catch { body = redactText(text); }
  }
  return {status: response.status, ok: response.ok, body: redactValue(body)};
}

export function compareLongFile(workspaceBytes, prBytes, options = {}) {
  const minimumLines = options.minimumLines ?? 151;
  const sentinel = options.sentinel ?? 'LITMUS_EOF_SENTINEL';
  const workspace = Buffer.isBuffer(workspaceBytes) ? workspaceBytes : Buffer.from(workspaceBytes);
  const pr = Buffer.isBuffer(prBytes) ? prBytes : Buffer.from(prBytes);
  const text = pr.toString('utf8');
  const lines = text.split(/\r?\n/).length;
  const result = {
    workspaceSha256: sha256(workspace),
    prSha256: sha256(pr),
    byteExact: workspace.equals(pr),
    lines,
    minimumLines,
    sentinelPresent: text.includes(sentinel),
  };
  result.pass = result.byteExact && lines >= minimumLines && result.sentinelPresent;
  return result;
}

export function captureDeltaSnapshot(value) {
  const sorted = (items) => [...new Set(items ?? [])].sort();
  return {
    runIds: sorted(value.runIds),
    architectureJobIds: sorted(value.architectureJobIds),
    documentJobIds: sorted(value.documentJobIds),
    managedUsageEventIds: sorted(value.managedUsageEventIds),
    prNumbers: sorted(value.prNumbers),
    mainSha: value.mainSha ?? null,
    projectCostUsd: Number(value.projectCostUsd ?? 0),
    physicalAttempts: Number(value.physicalAttempts ?? 0),
    runCostUsd: Number(value.runCostUsd ?? 0),
  };
}

export function assertNoUnexpectedDelta(beforeInput, afterInput, allowed = {}) {
  const before = captureDeltaSnapshot(beforeInput);
  const after = captureDeltaSnapshot(afterInput);
  const changed = [];
  for (const key of ['runIds', 'architectureJobIds', 'documentJobIds', 'managedUsageEventIds', 'prNumbers', 'mainSha']) {
    if (canonicalJson(before[key]) !== canonicalJson(after[key]) && !allowed[key]) changed.push(key);
  }
  for (const key of ['projectCostUsd', 'physicalAttempts', 'runCostUsd']) {
    if (Math.abs(before[key] - after[key]) > 1e-9 && !allowed[key]) changed.push(key);
  }
  if (changed.length) throw new Error(`Unexpected negative-test side effects: ${changed.join(', ')}`);
  return {pass: true, before, after};
}

export function scoreDocument(content, rubric, facts = {}) {
  const text = String(content ?? '');
  const lower = text.toLowerCase();
  const checks = [];
  const add = (id, points, pass, evidence) => checks.push({id, points, earned: pass ? points : 0, pass, evidence});
  add('repo-identity', 10, !facts.repoFullName || lower.includes(facts.repoFullName.toLowerCase()), facts.repoFullName ?? 'not supplied');
  add('sha-provenance', 10, !facts.sourceSha || lower.includes(facts.sourceSha.toLowerCase()), facts.sourceSha ?? 'not supplied');
  add('install-command', 10, lower.includes('npm ci'), 'npm ci');
  add('test-command', 10, lower.includes('npm test'), 'npm test');
  add('run-command', 10, lower.includes('npm start'), 'npm start');
  add('entrypoint', 15, lower.includes('src/catalog.js'), 'src/catalog.js');
  add('function-fact', 10, lower.includes('filtercatalog'), 'filterCatalog');
  add('setup-coverage', 10, /prerequisite|node\s*20|node\.js\s*20/i.test(text), 'Node 20 prerequisite');
  add('troubleshooting', 5, /troubleshoot|if .*fails|error/i.test(text), 'troubleshooting');
  const forbidden = (rubric.forbiddenPatterns ?? []).filter((pattern) => new RegExp(pattern, 'i').test(text));
  add('safety', 10, forbidden.length === 0, forbidden.length ? forbidden.join(', ') : 'no forbidden material');
  const score = checks.reduce((sum, check) => sum + check.earned, 0);
  const criticalFailures = checks.filter((check) => ['safety', 'entrypoint', 'function-fact'].includes(check.id) && !check.pass).map((check) => check.id);
  return {score, threshold: rubric.passThreshold ?? 85, checks, criticalFailures, pass: score >= (rubric.passThreshold ?? 85) && criticalFailures.length === 0};
}
