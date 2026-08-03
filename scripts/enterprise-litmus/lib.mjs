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

export const SCHEMA_VERSION = 2;
export const SOURCE_REVISION_PATCH_SHA256 = '2106e27f7ff5e55a51c18f7ccb39f7c814788a45918dc5eb094b67a52ecb07bd';
const TOP_LEVEL_KEYS = new Set([
  'schemaVersion', 'campaignId', 'state', 'createdAt', 'updatedAt', 'invalidation',
  'deployment', 'fixture', 'project', 'route', 'runRequest', 'approval', 'clients',
  'frozenTupleDigest', 'heroes', 'postMerge', 'negatives', 'cleanup', 'events',
]);
export const NEGATIVE_CASE_REGISTRY = Object.freeze({
  INVALID_REQUEST: {publiclyExecutable: true, statuses: [400, 422]},
  PLAN_REJECTED: {publiclyExecutable: true, statuses: [200]},
  USER_CANCELLED: {publiclyExecutable: true, statuses: [200]},
  LIFECYCLE_CONFLICT: {publiclyExecutable: true, statuses: [409]},
  PROJECT_PROVIDER_NOT_CONFIGURED: {publiclyExecutable: false, statuses: [409]},
  PROJECT_PROVIDER_INVALID: {publiclyExecutable: false, statuses: [409]},
  MODEL_MATRIX_UNAVAILABLE: {publiclyExecutable: false, statuses: [503]},
  POLICY_BLOCKED: {publiclyExecutable: true, statuses: [403, 409]},
  MANAGED_AI_BUDGET_EXHAUSTED: {publiclyExecutable: true, statuses: [402, 409], sseType: 'MANAGED_AI_BUDGET_EXHAUSTED'},
  RUN_COST_CAP_EXCEEDED: {publiclyExecutable: true, statuses: [402, 409], sseType: 'RUN_COST_CAP_EXCEEDED'},
  WORKSPACE_UNAVAILABLE_409: {publiclyExecutable: true, statuses: [409]},
  WORKSPACE_GONE_410: {publiclyExecutable: true, statuses: [410]},
  REPOSITORY_NOT_FOUND_404: {publiclyExecutable: true, statuses: [404]},
  TENANT_FORBIDDEN_403: {publiclyExecutable: true, statuses: [403]},
  AUTH_UNAUTHORIZED_401: {publiclyExecutable: true, statuses: [401]},
  DEPENDENCY_UNAVAILABLE_503: {publiclyExecutable: true, statuses: [503]},
  LOCAL_HANDS_DENIED: {publiclyExecutable: true, statuses: [403, 409]},
  INTERNAL_FAILURE_CONTROLLED: {publiclyExecutable: true, statuses: [409, 422]},
  CORRUPT_MODEL_MATRIX_FIXTURE: {publiclyExecutable: false, statuses: [503]},
  CORRUPT_WORKSPACE_FIXTURE: {publiclyExecutable: false, statuses: [409, 410]},
  CORRUPT_INTERNAL_FIXTURE: {publiclyExecutable: false, statuses: [409, 422]},
});
const REQUIRED_NEGATIVE_CASES = Object.freeze(Object.keys(NEGATIVE_CASE_REGISTRY));
const FROZEN_RUN_REQUEST_KEYS = Object.freeze([
  'userRequest', 'source', 'repoMode', 'skipApproval', 'autoMerge', 'executionMode',
  'modelName', 'aiProvider', 'projectId', 'selectedRepoIds', 'tddMode', 'userCostCap',
  'skipAutoPush', 'requireConfirmationBeforeExpensiveContinuation',
  'requireBenchmarkPriorForAutomaticContinuation',
]);
const SEMANTIC_FALLBACK_CODES = Object.freeze(new Set([
  'OWN_KEYS_OPENAI_CREDENTIAL_UNAVAILABLE',
  'OPENAI_CREDENTIAL_UNAVAILABLE',
  'AUTHORIZED_OPENAI_CREDENTIAL_UNAVAILABLE',
  'SELECTED_PROVIDER_DISABLED_BY_ORG',
  // Retained only so an older deployed build can be measured honestly during rollout.
  'EMBEDDING_SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL',
]));
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

function assertDigest(value, label) {
  if (!/^[0-9a-f]{64}$/i.test(String(value ?? ''))) throw new Error(`${label} must be an exact SHA-256 digest`);
}

function assertString(value, label) {
  if (!String(value ?? '').trim()) throw new Error(`${label} is required`);
}

function assertBoolean(value, expected, label) {
  if (value !== expected) throw new Error(`${label} must be ${expected}`);
}

function assertExactKeys(value, required, optional = [], label = 'evidence') {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} must be an object`);
  const allowed = new Set([...required, ...optional]);
  const missing = required.filter((key) => !(key in value));
  const unknown = Object.keys(value).filter((key) => !allowed.has(key));
  if (missing.length) throw new Error(`${label} is missing: ${missing.join(', ')}`);
  if (unknown.length) throw new Error(`${label} has unknown fields: ${unknown.join(', ')}`);
  return value;
}

function assertPositiveNumber(value, label, {allowZero = false} = {}) {
  if (!Number.isFinite(value) || (allowZero ? value < 0 : value <= 0)) throw new Error(`${label} must be a finite ${allowZero ? 'non-negative' : 'positive'} number`);
}

function assertHeroRoute(route, manifest, label) {
  assertExactKeys(route, ['provider', 'model', 'routingMode'], [], label);
  if (canonicalJson(route) !== canonicalJson(manifest.route)) throw new Error(`${label} contradicts frozen route`);
}

function materializeFrozenRunRequest(request, manifest, label) {
  assertExactKeys(request, FROZEN_RUN_REQUEST_KEYS, [], label);
  assertString(request.userRequest, `${label}.userRequest`);
  if (request.userRequest.trim() !== String(manifest.runRequest.userRequest ?? '').trim()) {
    throw new Error(`${label}.userRequest changed from the initialized requirement`);
  }
  if (request.source !== 'GITHUB_EXISTING' || request.repoMode !== 'EXISTING') {
    throw new Error(`${label} must target the linked existing GitHub repository`);
  }
  if (request.projectId !== manifest.project.projectId
      || !Array.isArray(request.selectedRepoIds)
      || request.selectedRepoIds.length !== 1
      || request.selectedRepoIds[0] !== manifest.project.repoId) {
    throw new Error(`${label} must bind the exact linked project and repository`);
  }
  if (request.executionMode !== 'PRODUCTION' || request.skipApproval !== false
      || request.autoMerge !== false || request.skipAutoPush !== false || request.tddMode !== false) {
    throw new Error(`${label} must use production, manual approval/merge and automatic publication`);
  }
  if (request.aiProvider !== manifest.route.provider || request.modelName !== manifest.route.model) {
    throw new Error(`${label} must use the exact frozen provider/model route`);
  }
  if (request.requireConfirmationBeforeExpensiveContinuation !== true
      || request.requireBenchmarkPriorForAutomaticContinuation !== false) {
    throw new Error(`${label} continuation policy contradicts the frozen campaign`);
  }
  assertPositiveNumber(request.userCostCap, `${label}.userCostCap`);
  return {
    ...structuredClone(request),
    requirementSha256: sha256(request.userRequest.trim()),
    canonicalJsonSha256: sha256(canonicalJson(request)),
  };
}

function validateHeroProof(proof, manifest, ordinal) {
  const label = `hero ${ordinal}`;
  assertExactKeys(proof, [
    'ordinal', 'runId', 'branch', 'sourceSha', 'requestSha256', 'status', 'plan',
    'approvalReceiptId', 'task', 'files', 'tests', 'review', 'pr', 'cost', 'route',
    'routeCalls', 'longFile', 'reconnect', 'parity', 'terminal', 'correctnessEvidenceId',
  ], [], label);
  if (proof.ordinal !== ordinal) throw new Error(`${label}.ordinal must be ${ordinal}`);
  assertString(proof.runId, `${label}.runId`);
  assertString(proof.branch, `${label}.branch`);
  assertSha(proof.sourceSha, `${label}.sourceSha`);
  if (proof.sourceSha !== manifest.fixture.currentBaseSha) throw new Error(`${label} did not start from the frozen fixture base`);
  if (proof.status !== 'COMPLETE') throw new Error(`${label}.status must be COMPLETE`);
  if (proof.requestSha256 !== manifest.runRequest.canonicalJsonSha256) throw new Error(`${label}.requestSha256 contradicts frozen request`);
  assertExactKeys(proof.plan, ['planId', 'version', 'fingerprint'], [], `${label}.plan`);
  assertString(proof.plan.planId, `${label}.plan.planId`);
  if (!Number.isInteger(proof.plan.version) || proof.plan.version < 1) throw new Error(`${label}.plan.version must be positive`);
  assertDigest(proof.plan.fingerprint, `${label}.plan.fingerprint`);
  assertString(proof.approvalReceiptId, `${label}.approvalReceiptId`);
  assertExactKeys(proof.task, ['total', 'completed', 'failed', 'buildPassed', 'evidenceId'], [], `${label}.task`);
  if (!Number.isInteger(proof.task.total) || proof.task.total < 1 || proof.task.completed !== proof.task.total || proof.task.failed !== 0) throw new Error(`${label} task evidence is incomplete`);
  assertBoolean(proof.task.buildPassed, true, `${label}.task.buildPassed`);
  assertString(proof.task.evidenceId, `${label}.task.evidenceId`);
  assertExactKeys(proof.files, ['changed', 'generatedCodeSha256'], [], `${label}.files`);
  if (!Array.isArray(proof.files.changed) || proof.files.changed.length === 0 || proof.files.changed.some((item) => !String(item).trim())) {
    throw new Error(`${label}.files.changed must contain generated files`);
  }
  assertDigest(proof.files.generatedCodeSha256, `${label}.files.generatedCodeSha256`);
  assertExactKeys(proof.tests, ['passed', 'failed', 'skipped', 'behaviorVerified'], [], `${label}.tests`);
  if (!Number.isInteger(proof.tests.passed) || proof.tests.passed < 1 || proof.tests.failed !== 0 || proof.tests.skipped !== 0) {
    throw new Error(`${label} must have at least one passing test and zero failed/skipped tests`);
  }
  assertBoolean(proof.tests.behaviorVerified, true, `${label}.tests.behaviorVerified`);
  assertExactKeys(proof.review, ['truthful', 'verifiedScore', 'evidenceId'], [], `${label}.review`);
  assertBoolean(proof.review.truthful, true, `${label}.review.truthful`);
  assertPositiveNumber(proof.review.verifiedScore, `${label}.review.verifiedScore`);
  if (proof.review.verifiedScore > 100) throw new Error(`${label}.review.verifiedScore cannot exceed 100`);
  assertString(proof.review.evidenceId, `${label}.review.evidenceId`);
  assertExactKeys(proof.pr, ['number', 'url', 'branch', 'baseSha', 'headSha', 'ci'], [], `${label}.pr`);
  if (!Number.isInteger(proof.pr.number) || proof.pr.number < 1) throw new Error(`${label}.pr.number must be positive`);
  if (proof.pr.url !== `https://github.com/${manifest.fixture.repoFullName}/pull/${proof.pr.number}`) {
    throw new Error(`${label}.pr.url must identify the frozen fixture PR`);
  }
  if (proof.pr.branch !== proof.branch || proof.pr.baseSha !== manifest.fixture.currentBaseSha) throw new Error(`${label} PR branch/base contradicts hero`);
  assertSha(proof.pr.headSha, `${label}.pr.headSha`);
  assertExactKeys(proof.pr.ci, ['checkName', 'conclusion', 'totalChecks', 'headSha'], [], `${label}.pr.ci`);
  if (proof.pr.ci.checkName !== 'fixture-ci' || proof.pr.ci.conclusion !== 'SUCCESS' || proof.pr.ci.totalChecks !== 1) {
    throw new Error(`${label} must prove exactly one green fixture-ci check`);
  }
  if (proof.pr.ci.headSha !== proof.pr.headSha) throw new Error(`${label}.pr.ci.headSha contradicts PR head`);
  assertExactKeys(proof.cost, ['actualUsd', 'capUsd', 'bounded'], [], `${label}.cost`);
  assertPositiveNumber(proof.cost.actualUsd, `${label}.cost.actualUsd`, {allowZero: true});
  assertPositiveNumber(proof.cost.capUsd, `${label}.cost.capUsd`);
  if (proof.cost.actualUsd > proof.cost.capUsd || proof.cost.capUsd !== manifest.runRequest.userCostCap) {
    throw new Error(`${label} cost is not bounded by the frozen cap`);
  }
  assertBoolean(proof.cost.bounded, true, `${label}.cost.bounded`);
  assertHeroRoute(proof.route, manifest, `${label}.route`);
  if (!Array.isArray(proof.routeCalls) || proof.routeCalls.length < 1) throw new Error(`${label}.routeCalls must contain the physical call ledger`);
  const callIds = new Set();
  for (const call of proof.routeCalls) {
    assertExactKeys(call, ['callId', 'provider', 'model', 'agentRole', 'noFallback'], [], `${label}.routeCalls[]`);
    assertString(call.callId, `${label}.routeCalls[].callId`);
    if (callIds.has(call.callId)) throw new Error(`${label}.routeCalls contains duplicate callId`);
    callIds.add(call.callId);
    if (call.provider !== manifest.route.provider || call.model !== manifest.route.model) throw new Error(`${label}.routeCalls contradict frozen route`);
    assertString(call.agentRole, `${label}.routeCalls[].agentRole`);
    assertBoolean(call.noFallback, true, `${label}.routeCalls[].noFallback`);
  }
  for (const role of ['PLANNER', 'WORKER', 'REVIEWER']) {
    if (!proof.routeCalls.some((call) => call.agentRole === role)) throw new Error(`${label}.routeCalls is missing ${role}`);
  }
  assertExactKeys(proof.longFile, ['byteExact', 'lines', 'sentinelPresent', 'workspaceSha256', 'prSha256', 'pass'], [], `${label}.longFile`);
  assertBoolean(proof.longFile.byteExact, true, `${label}.longFile.byteExact`);
  assertBoolean(proof.longFile.sentinelPresent, true, `${label}.longFile.sentinelPresent`);
  assertBoolean(proof.longFile.pass, true, `${label}.longFile.pass`);
  if (!Number.isInteger(proof.longFile.lines) || proof.longFile.lines < 151) throw new Error(`${label}.longFile.lines must be >= 151`);
  assertDigest(proof.longFile.workspaceSha256, `${label}.longFile.workspaceSha256`);
  if (proof.longFile.prSha256 !== proof.longFile.workspaceSha256) throw new Error(`${label} long-file digests contradict byteExact=true`);
  assertExactKeys(proof.reconnect, ['sseRecovered', 'reloadStable', 'evidenceId'], [], `${label}.reconnect`);
  assertBoolean(proof.reconnect.sseRecovered, true, `${label}.reconnect.sseRecovered`);
  assertBoolean(proof.reconnect.reloadStable, true, `${label}.reconnect.reloadStable`);
  assertString(proof.reconnect.evidenceId, `${label}.reconnect.evidenceId`);
  assertExactKeys(proof.parity, ['web', 'cli', 'terminalOutcomeDigest'], [], `${label}.parity`);
  assertBoolean(proof.parity.web, true, `${label}.parity.web`);
  assertBoolean(proof.parity.cli, true, `${label}.parity.cli`);
  assertDigest(proof.parity.terminalOutcomeDigest, `${label}.parity.terminalOutcomeDigest`);
  assertExactKeys(proof.terminal, ['onePrimaryAction', 'actionCode', 'supportCode', 'webCliParity', 'evidenceId'], [], `${label}.terminal`);
  assertBoolean(proof.terminal.onePrimaryAction, true, `${label}.terminal.onePrimaryAction`);
  assertString(proof.terminal.actionCode, `${label}.terminal.actionCode`);
  assertString(proof.terminal.supportCode, `${label}.terminal.supportCode`);
  assertBoolean(proof.terminal.webCliParity, true, `${label}.terminal.webCliParity`);
  assertString(proof.terminal.evidenceId, `${label}.terminal.evidenceId`);
  assertString(proof.correctnessEvidenceId, `${label}.correctnessEvidenceId`);
}

function validateDistinctHeroes(heroes) {
  if (heroes.length !== 3) throw new Error('Exactly three verified heroes are required');
  for (const key of ['runId', 'approvalReceiptId', 'correctnessEvidenceId']) {
    if (new Set(heroes.map((hero) => hero[key])).size !== 3) throw new Error(`Heroes must have distinct ${key} values`);
  }
  if (new Set(heroes.map((hero) => hero.pr.number)).size !== 3) throw new Error('Heroes must have distinct PR numbers');
  if (new Set(heroes.map((hero) => hero.pr.headSha)).size !== 3) throw new Error('Heroes must have distinct PR head SHAs');
  if (new Set(heroes.map((hero) => hero.branch)).size !== 3) throw new Error('Heroes must have distinct branches');
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
    approval: input.approval ?? {
      mode: 'MANUAL_EXACT_PLAN',
      fingerprintAlgorithm: 'SHA-256_UTF8_PLAN_JSON',
      fingerprintRequired: true,
      creditsConfirmationRequired: true,
      decisionContractSha256: '',
    },
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
      || manifest.approval?.fingerprintAlgorithm !== 'SHA-256_UTF8_PLAN_JSON'
      || manifest.approval?.fingerprintRequired !== true
      || manifest.approval?.creditsConfirmationRequired !== true) {
    throw new Error('Litmus approval must use the exact manual-plan fingerprint contract');
  }
  if (!Array.isArray(manifest.heroes) || !Array.isArray(manifest.negatives) || !Array.isArray(manifest.events)) {
    throw new Error('Manifest evidence collections must be arrays');
  }
  if (manifest.heroes.length > 3) throw new Error('Manifest cannot contain more than three heroes');
  manifest.heroes.forEach((hero, index) => validateHeroProof(hero, manifest, index + 1));
  if (manifest.heroes.length > 1) {
    const partialKeys = ['runId', 'approvalReceiptId', 'correctnessEvidenceId'];
    for (const key of partialKeys) {
      if (new Set(manifest.heroes.map((hero) => hero[key])).size !== manifest.heroes.length) {
        throw new Error(`Heroes must have distinct ${key} values`);
      }
    }
  }
  if (manifest.state === 'FROZEN' || manifest.frozenTupleDigest) assertFrozenTuple(manifest);
  if (manifest.state === 'COMPLETE') auditManifestCompleteness(manifest);
  return manifest;
}

function materializeEvidence(manifest, next, evidence) {
  const updated = structuredClone(manifest);
  switch (next) {
    case 'PREFLIGHTED': {
      assertExactKeys(evidence, [
        'expectedSha', 'deployedSha', 'runningSha', 'runningShaAuthority', 'health',
        'deployWorkflowRunId', 'deployWorkflowRunUrl', 'deployConclusion',
        'providerState', 'providerPolicyEvidenceId', 'ghAuth',
      ], [], next);
      assertSha(evidence.expectedSha, 'PREFLIGHTED.expectedSha');
      assertSha(evidence.deployedSha, 'PREFLIGHTED.deployedSha');
      assertSha(evidence.runningSha, 'PREFLIGHTED.runningSha');
      if (new Set([evidence.expectedSha, evidence.deployedSha, evidence.runningSha]).size !== 1) throw new Error('PREFLIGHTED expected/deployed/running SHA mismatch');
      if (evidence.runningShaAuthority !== 'DEPLOYMENT_HOST:/opt/app/RUNNING_SHA') throw new Error('PREFLIGHTED.runningShaAuthority must identify /opt/app/RUNNING_SHA');
      if (!['UP', 'DEGRADED'].includes(evidence.health)) throw new Error('PREFLIGHTED.health must be UP or DEGRADED');
      assertString(evidence.deployWorkflowRunId, 'PREFLIGHTED.deployWorkflowRunId');
      if (!/^https:\/\/github\.com\/[^/]+\/[^/]+\/actions\/runs\/\d+$/.test(evidence.deployWorkflowRunUrl)) throw new Error('PREFLIGHTED.deployWorkflowRunUrl must be an exact GitHub Actions run URL');
      if (evidence.deployConclusion !== 'SUCCESS') throw new Error('PREFLIGHTED.deployConclusion must be SUCCESS');
      assertString(evidence.providerState, 'PREFLIGHTED.providerState');
      assertString(evidence.providerPolicyEvidenceId, 'PREFLIGHTED.providerPolicyEvidenceId');
      if (evidence.ghAuth !== 'VERIFIED') throw new Error('PREFLIGHTED.ghAuth must be VERIFIED');
      updated.deployment = {
        ...updated.deployment,
        expectedSha: evidence.expectedSha,
        deployedSha: evidence.deployedSha,
        runningSha: evidence.runningSha,
        runningShaAuthority: evidence.runningShaAuthority,
        deployWorkflowRunId: evidence.deployWorkflowRunId,
        deployWorkflowRunUrl: evidence.deployWorkflowRunUrl,
        deployConclusion: evidence.deployConclusion,
        health: evidence.health,
        providerState: evidence.providerState,
        providerPolicyEvidenceId: evidence.providerPolicyEvidenceId,
      };
      break;
    }
    case 'FIXTURE_CREATED': {
      assertExactKeys(evidence, ['repoFullName', 'baseBranch', 'visibility', 'baseSha'], [], next);
      assertString(evidence.repoFullName, 'FIXTURE_CREATED.repoFullName');
      if (evidence.baseBranch !== 'main' || evidence.visibility !== 'PRIVATE') throw new Error('Fixture must be a private repository on main');
      assertSha(evidence.baseSha, 'FIXTURE_CREATED.baseSha');
      updated.fixture = {...updated.fixture, ...evidence, currentBaseSha: evidence.baseSha};
      break;
    }
    case 'PROJECT_LINKED': {
      assertExactKeys(evidence, ['projectId', 'repoId', 'linkEvidenceId'], [], next);
      assertString(evidence.projectId, 'PROJECT_LINKED.projectId');
      assertString(evidence.repoId, 'PROJECT_LINKED.repoId');
      assertString(evidence.linkEvidenceId, 'PROJECT_LINKED.linkEvidenceId');
      updated.project = {...updated.project, ...evidence};
      break;
    }
    case 'BASELINE_V1_READY':
    case 'BASELINE_V2_READY': {
      const version = next === 'BASELINE_V1_READY' ? 'baselineV1' : 'baselineV2';
      const required = [
        'architectureVersionId', 'sourceSha', 'codeMapEvidenceId', 'architectureEvidenceId',
        'documentEvidenceId', 'documentRubricPass', 'documentScore', 'documentMaxScore',
        'documentSourceSha', 'documentProvider', 'documentModel', 'documentOperationId',
      ];
      if (next === 'BASELINE_V2_READY') required.push(
        'webSha', 'cliSha', 'decisionContractSha256', 'compareFromVersionId', 'compareToVersionId',
        'contentDiffPass', 'fileDiffOnly', 'contentDiffSha256', 'expectedChangedLineEvidenceId',
        'semanticErrorCode', 'ripgrepAvailable', 'semanticProviderCallsDelta', 'semanticFallbackEvidenceId',
        'runRequest',
      );
      assertExactKeys(evidence, required, [], next);
      assertString(evidence.architectureVersionId, `${next}.architectureVersionId`);
      assertSha(evidence.sourceSha, `${next}.sourceSha`);
      assertString(evidence.codeMapEvidenceId, `${next}.codeMapEvidenceId`);
      assertString(evidence.architectureEvidenceId, `${next}.architectureEvidenceId`);
      assertString(evidence.documentEvidenceId, `${next}.documentEvidenceId`);
      assertBoolean(evidence.documentRubricPass, true, `${next}.documentRubricPass`);
      assertPositiveNumber(evidence.documentScore, `${next}.documentScore`);
      assertPositiveNumber(evidence.documentMaxScore, `${next}.documentMaxScore`);
      if (evidence.documentScore > evidence.documentMaxScore || evidence.documentScore / evidence.documentMaxScore < 0.8) throw new Error(`${next} document score must be at least 80%`);
      assertSha(evidence.documentSourceSha, `${next}.documentSourceSha`);
      if (evidence.documentSourceSha !== evidence.sourceSha) throw new Error(`${next} document source contradicts architecture source`);
      if (evidence.documentProvider !== updated.route.provider || evidence.documentModel !== updated.route.model) throw new Error(`${next} document provenance contradicts route`);
      assertString(evidence.documentOperationId, `${next}.documentOperationId`);
      if (next === 'BASELINE_V1_READY' && evidence.sourceSha !== updated.fixture.baseSha) throw new Error('Baseline V1 must bind the fixture base SHA');
      if (next === 'BASELINE_V2_READY' && evidence.sourceSha !== updated.fixture.currentBaseSha) throw new Error('Baseline V2 must bind the current fixture base SHA');
      updated.project[version] = structuredClone(evidence);
      if (next === 'BASELINE_V2_READY') {
        assertSha(evidence.webSha, 'BASELINE_V2_READY.webSha');
        assertSha(evidence.cliSha, 'BASELINE_V2_READY.cliSha');
        assertDigest(evidence.decisionContractSha256, 'BASELINE_V2_READY.decisionContractSha256');
        if (evidence.compareFromVersionId !== updated.project.baselineV1?.architectureVersionId
            || evidence.compareToVersionId !== evidence.architectureVersionId) {
          throw new Error('BASELINE_V2_READY Compare History versions contradict the baselines');
        }
        assertBoolean(evidence.contentDiffPass, true, 'BASELINE_V2_READY.contentDiffPass');
        assertBoolean(evidence.fileDiffOnly, false, 'BASELINE_V2_READY.fileDiffOnly');
        assertDigest(evidence.contentDiffSha256, 'BASELINE_V2_READY.contentDiffSha256');
        assertString(evidence.expectedChangedLineEvidenceId, 'BASELINE_V2_READY.expectedChangedLineEvidenceId');
        if (!SEMANTIC_FALLBACK_CODES.has(evidence.semanticErrorCode)) {
          throw new Error('BASELINE_V2_READY.semanticErrorCode must be a governed lexical-fallback reason');
        }
        assertBoolean(evidence.ripgrepAvailable, true, 'BASELINE_V2_READY.ripgrepAvailable');
        if (evidence.semanticProviderCallsDelta !== 0) throw new Error('BASELINE_V2_READY semantic fallback must not call a provider');
        assertString(evidence.semanticFallbackEvidenceId, 'BASELINE_V2_READY.semanticFallbackEvidenceId');
        updated.clients = {webSha: evidence.webSha, cliSha: evidence.cliSha};
        updated.approval.decisionContractSha256 = evidence.decisionContractSha256;
        updated.runRequest = materializeFrozenRunRequest(
          evidence.runRequest,
          updated,
          'BASELINE_V2_READY.runRequest',
        );
        delete updated.project[version].webSha;
        delete updated.project[version].cliSha;
        delete updated.project[version].decisionContractSha256;
        delete updated.project[version].runRequest;
      }
      break;
    }
    case 'SOURCE_REVISION_PUSHED': {
      assertExactKeys(evidence, [
        'previousSha', 'currentBaseSha', 'patchSha256', 'changedPaths', 'checkName',
        'checkConclusion', 'ciEvidenceId', 'pushEvidenceId',
      ], [], next);
      assertSha(evidence.previousSha, 'SOURCE_REVISION_PUSHED.previousSha');
      assertSha(evidence.currentBaseSha, 'SOURCE_REVISION_PUSHED.currentBaseSha');
      if (evidence.previousSha !== updated.fixture.baseSha || evidence.currentBaseSha === evidence.previousSha) {
        throw new Error('Source revision must advance exactly from the fixture base');
      }
      if (evidence.patchSha256 !== SOURCE_REVISION_PATCH_SHA256) {
        throw new Error('SOURCE_REVISION_PUSHED.patchSha256 must bind the committed deterministic patch');
      }
      if (canonicalJson(evidence.changedPaths) !== canonicalJson(['src/catalog.js', 'test/baseline.test.js'])) {
        throw new Error('SOURCE_REVISION_PUSHED.changedPaths must match the deterministic patch');
      }
      if (evidence.checkName !== 'fixture-ci' || evidence.checkConclusion !== 'SUCCESS') {
        throw new Error('SOURCE_REVISION_PUSHED must have one green fixture-ci result');
      }
      assertString(evidence.ciEvidenceId, 'SOURCE_REVISION_PUSHED.ciEvidenceId');
      assertString(evidence.pushEvidenceId, 'SOURCE_REVISION_PUSHED.pushEvidenceId');
      updated.fixture.currentBaseSha = evidence.currentBaseSha;
      updated.fixture.sourceRevisionPatchSha256 = evidence.patchSha256;
      updated.fixture.sourceRevisionChangedPaths = structuredClone(evidence.changedPaths);
      updated.fixture.sourceRevisionCiEvidenceId = evidence.ciEvidenceId;
      updated.fixture.sourceRevisionEvidenceId = evidence.pushEvidenceId;
      break;
    }
    case 'HERO_1_RUNNING':
    case 'HERO_2_RUNNING':
    case 'HERO_3_RUNNING': {
      const ordinal = Number(next.match(/HERO_(\d)/)?.[1]);
      assertExactKeys(evidence, ['ordinal', 'runId', 'branch', 'sourceSha', 'requestSha256', 'submittedEvidenceId'], [], next);
      if (evidence.ordinal !== ordinal) throw new Error(`${next}.ordinal must be ${ordinal}`);
      assertString(evidence.runId, `${next}.runId`);
      assertString(evidence.branch, `${next}.branch`);
      assertSha(evidence.sourceSha, `${next}.sourceSha`);
      assertDigest(evidence.requestSha256, `${next}.requestSha256`);
      assertString(evidence.submittedEvidenceId, `${next}.submittedEvidenceId`);
      if (evidence.sourceSha !== updated.fixture.currentBaseSha || evidence.requestSha256 !== updated.runRequest.canonicalJsonSha256) {
        throw new Error(`${next} contradicts the frozen source/request`);
      }
      if (updated.heroes.some((hero) => hero.runId === evidence.runId || hero.branch === evidence.branch) || updated.postMerge.pendingHero) {
        throw new Error('Duplicate hero run or unclosed hero submission');
      }
      updated.postMerge.pendingHero = structuredClone(evidence);
      break;
    }
    case 'HERO_1_VERIFIED':
    case 'HERO_2_VERIFIED':
    case 'HERO_3_VERIFIED': {
      const ordinal = Number(next.match(/HERO_(\d)/)?.[1]);
      validateHeroProof(evidence, updated, ordinal);
      if (!updated.postMerge.pendingHero || updated.postMerge.pendingHero.runId !== evidence.runId
          || updated.postMerge.pendingHero.branch !== evidence.branch || updated.postMerge.pendingHero.ordinal !== ordinal) {
        throw new Error(`${next} does not close the matching submitted hero`);
      }
      updated.heroes.push(structuredClone(evidence));
      updated.postMerge.heroSubmissions ??= [];
      updated.postMerge.heroSubmissions.push(structuredClone(updated.postMerge.pendingHero));
      delete updated.postMerge.pendingHero;
      if (updated.heroes.length !== ordinal) throw new Error('Hero sequence is incomplete or duplicated');
      break;
    }
    case 'HERO_PR_MERGED': {
      assertExactKeys(evidence, ['heroOrdinal', 'runId', 'prNumber', 'preMergeSha', 'mergedSha', 'mergeEvidenceId'], [], next);
      validateDistinctHeroes(updated.heroes);
      const hero = updated.heroes[evidence.heroOrdinal - 1];
      if (!hero || hero.runId !== evidence.runId || hero.pr.number !== evidence.prNumber) throw new Error('Merged PR does not identify a verified hero');
      assertSha(evidence.preMergeSha, 'HERO_PR_MERGED.preMergeSha');
      assertSha(evidence.mergedSha, 'HERO_PR_MERGED.mergedSha');
      if (evidence.preMergeSha !== updated.fixture.currentBaseSha || evidence.mergedSha === evidence.preMergeSha) throw new Error('Merged SHA must advance frozen main');
      assertString(evidence.mergeEvidenceId, 'HERO_PR_MERGED.mergeEvidenceId');
      updated.postMerge.merge = structuredClone(evidence);
      break;
    }
    case 'MERGED_SHA_REFRESHED': {
      assertExactKeys(evidence, ['observedMainSha', 'workspaceSha', 'architectureSourceSha', 'exactContentSha256', 'stalePublishPrevented', 'refreshEvidenceId'], [], next);
      const mergedSha = updated.postMerge.merge?.mergedSha;
      for (const key of ['observedMainSha', 'workspaceSha', 'architectureSourceSha']) {
        assertSha(evidence[key], `MERGED_SHA_REFRESHED.${key}`);
        if (evidence[key] !== mergedSha) throw new Error(`MERGED_SHA_REFRESHED.${key} must equal merged SHA`);
      }
      assertDigest(evidence.exactContentSha256, 'MERGED_SHA_REFRESHED.exactContentSha256');
      assertBoolean(evidence.stalePublishPrevented, true, 'MERGED_SHA_REFRESHED.stalePublishPrevented');
      assertString(evidence.refreshEvidenceId, 'MERGED_SHA_REFRESHED.refreshEvidenceId');
      updated.postMerge.refresh = structuredClone(evidence);
      break;
    }
    case 'CONTINUATION_VERIFIED': {
      assertExactKeys(evidence, ['runId', 'parentRunId', 'sourceSha', 'status', 'testsPassed', 'testsFailed', 'prNumber', 'continuationEvidenceId'], [], next);
      assertString(evidence.runId, 'CONTINUATION_VERIFIED.runId');
      assertString(evidence.parentRunId, 'CONTINUATION_VERIFIED.parentRunId');
      if (evidence.runId === evidence.parentRunId || updated.heroes.some((hero) => hero.runId === evidence.runId)) throw new Error('Continuation run must be distinct');
      assertSha(evidence.sourceSha, 'CONTINUATION_VERIFIED.sourceSha');
      if (evidence.sourceSha !== updated.postMerge.merge?.mergedSha || evidence.status !== 'COMPLETE') throw new Error('Continuation must complete from merged SHA');
      if (!Number.isInteger(evidence.testsPassed) || evidence.testsPassed < 1 || evidence.testsFailed !== 0) throw new Error('Continuation tests must pass');
      if (!Number.isInteger(evidence.prNumber) || evidence.prNumber < 1) throw new Error('Continuation PR is required');
      assertString(evidence.continuationEvidenceId, 'CONTINUATION_VERIFIED.continuationEvidenceId');
      updated.postMerge.continuation = structuredClone(evidence);
      break;
    }
    case 'CI_FAILURE_CREATED': {
      assertExactKeys(evidence, ['prNumber', 'headSha', 'checkName', 'conclusion', 'testContractSha256', 'failureEvidenceId'], [], next);
      if (!Number.isInteger(evidence.prNumber) || evidence.prNumber < 1 || evidence.checkName !== 'fixture-ci' || evidence.conclusion !== 'FAILURE') throw new Error('CI failure must identify failed fixture-ci');
      assertSha(evidence.headSha, 'CI_FAILURE_CREATED.headSha');
      assertDigest(evidence.testContractSha256, 'CI_FAILURE_CREATED.testContractSha256');
      assertString(evidence.failureEvidenceId, 'CI_FAILURE_CREATED.failureEvidenceId');
      updated.postMerge.ciFailure = structuredClone(evidence);
      break;
    }
    case 'CI_FIX_VERIFIED': {
      assertExactKeys(evidence, ['fixRunId', 'prNumber', 'headSha', 'checkName', 'conclusion', 'totalChecks', 'testsPassed', 'testsFailed', 'testContractSha256', 'testsWeakened', 'fixEvidenceId'], [], next);
      const failure = updated.postMerge.ciFailure;
      assertString(evidence.fixRunId, 'CI_FIX_VERIFIED.fixRunId');
      if (evidence.prNumber !== failure?.prNumber || evidence.checkName !== 'fixture-ci' || evidence.conclusion !== 'SUCCESS' || evidence.totalChecks !== 1) throw new Error('CI fix must green the same PR with one fixture-ci check');
      assertSha(evidence.headSha, 'CI_FIX_VERIFIED.headSha');
      if (evidence.headSha === failure.headSha) throw new Error('CI fix must produce a new head SHA');
      if (evidence.testContractSha256 !== failure.testContractSha256) throw new Error('CI fix changed the protected test contract');
      if (!Number.isInteger(evidence.testsPassed) || evidence.testsPassed < 1 || evidence.testsFailed !== 0) throw new Error('CI fix tests must pass');
      assertBoolean(evidence.testsWeakened, false, 'CI_FIX_VERIFIED.testsWeakened');
      assertString(evidence.fixEvidenceId, 'CI_FIX_VERIFIED.fixEvidenceId');
      updated.postMerge.ciFix = structuredClone(evidence);
      break;
    }
    case 'DEPLOYMENT_TRACKED': {
      assertExactKeys(evidence, ['deploymentId', 'sourceSha', 'status', 'trackingEvidenceId'], [], next);
      assertString(evidence.deploymentId, 'DEPLOYMENT_TRACKED.deploymentId');
      assertSha(evidence.sourceSha, 'DEPLOYMENT_TRACKED.sourceSha');
      if (evidence.sourceSha !== updated.postMerge.ciFix?.headSha || evidence.status !== 'SUCCEEDED') throw new Error('Tracked deployment must succeed for the CI-fixed SHA');
      assertString(evidence.trackingEvidenceId, 'DEPLOYMENT_TRACKED.trackingEvidenceId');
      updated.postMerge.deployment = {mode: 'TRACKED', ...structuredClone(evidence)};
      break;
    }
    case 'DEPLOYMENT_SKIPPED': {
      assertExactKeys(evidence, ['decisionId', 'reason', 'deploymentCreated', 'trackingChecked'], [], next);
      assertString(evidence.decisionId, 'DEPLOYMENT_SKIPPED.decisionId');
      assertString(evidence.reason, 'DEPLOYMENT_SKIPPED.reason');
      assertBoolean(evidence.deploymentCreated, false, 'DEPLOYMENT_SKIPPED.deploymentCreated');
      assertBoolean(evidence.trackingChecked, true, 'DEPLOYMENT_SKIPPED.trackingChecked');
      updated.postMerge.deployment = {mode: 'SKIPPED', ...structuredClone(evidence)};
      break;
    }
    case 'NEGATIVES_VERIFIED': {
      assertExactKeys(evidence, ['cases'], [], next);
      if (!Array.isArray(evidence.cases) || evidence.cases.length !== REQUIRED_NEGATIVE_CASES.length) throw new Error(`Exactly ${REQUIRED_NEGATIVE_CASES.length} negative cases are required`);
      const ids = evidence.cases.map((item) => item.caseId);
      if (new Set(ids).size !== ids.length || canonicalJson([...ids].sort()) !== canonicalJson([...REQUIRED_NEGATIVE_CASES].sort())) {
        throw new Error('Negative cases are missing, duplicated, or unknown');
      }
      for (const item of evidence.cases) {
        const label = `negative ${item.caseId}`;
        const registry = NEGATIVE_CASE_REGISTRY[item.caseId];
        if (item.status === 'NOT_RUN') {
          assertExactKeys(item, ['caseId', 'status', 'unsupportedReason', 'requiredAuthority', 'owner'], [], label);
          if (registry.publiclyExecutable) throw new Error(`${label} is publicly executable and cannot be NOT_RUN`);
          for (const key of ['unsupportedReason', 'requiredAuthority', 'owner']) assertString(item[key], `${label}.${key}`);
          continue;
        }
        assertExactKeys(item, [
          'caseId', 'status', 'httpStatus', 'terminalCode', 'controlled', 'providerCallsDelta',
          'chargeUsdDelta', 'codeChangesDelta', 'prCountDelta', 'snapshotBeforeSha256',
          'snapshotAfterSha256', 'oneAction', 'rawLeakAbsent', 'webCliParity', 'deltaEvidenceId',
        ], registry.sseType ? ['sseType'] : [], label);
        if (item.status !== 'VERIFIED') throw new Error(`${label}.status must be VERIFIED or an allowlisted NOT_RUN`);
        if (!registry.statuses.includes(item.httpStatus)) throw new Error(`${label}.httpStatus is not valid for this case`);
        if (registry.sseType && item.sseType !== registry.sseType) throw new Error(`${label}.sseType must be ${registry.sseType}`);
        assertString(item.terminalCode, `${label}.terminalCode`);
        assertBoolean(item.controlled, true, `${label}.controlled`);
        for (const key of ['providerCallsDelta', 'chargeUsdDelta', 'codeChangesDelta', 'prCountDelta']) {
          if (item[key] !== 0) throw new Error(`${label}.${key} must be zero`);
        }
        assertDigest(item.snapshotBeforeSha256, `${label}.snapshotBeforeSha256`);
        if (item.snapshotAfterSha256 !== item.snapshotBeforeSha256) throw new Error(`${label} before/after snapshots contradict zero delta`);
        assertBoolean(item.oneAction, true, `${label}.oneAction`);
        assertBoolean(item.rawLeakAbsent, true, `${label}.rawLeakAbsent`);
        assertBoolean(item.webCliParity, true, `${label}.webCliParity`);
        assertString(item.deltaEvidenceId, `${label}.deltaEvidenceId`);
      }
      const verifiedEvidenceIds = evidence.cases.filter((item) => item.status === 'VERIFIED').map((item) => item.deltaEvidenceId);
      if (new Set(verifiedEvidenceIds).size !== verifiedEvidenceIds.length) throw new Error('Verified negative cases need distinct delta evidence');
      updated.negatives = structuredClone(evidence.cases);
      break;
    }
    case 'CLEANUP_REQUIRED': {
      assertExactKeys(evidence, ['reason', 'cleanupPlanId'], [], next);
      assertString(evidence.reason, 'CLEANUP_REQUIRED.reason');
      assertString(evidence.cleanupPlanId, 'CLEANUP_REQUIRED.cleanupPlanId');
      updated.cleanup = {...updated.cleanup, required: structuredClone(evidence)};
      break;
    }
    case 'CLEANUP_VERIFIED': {
      assertExactKeys(evidence, [
        'githubRepo', 'githubAbsent', 'executespecProjectId', 'projectAbsent', 'repositoryLinkAbsent',
        'executespecRepositoryAbsent', 'sandboxAbsent', 'runsAbsent', 'deploymentAbsent', 'remoteBranchesAbsent',
        'localArtifactsRemoved', 'cleanupEvidenceId',
      ], [], next);
      if (evidence.githubRepo !== updated.fixture.repoFullName || evidence.executespecProjectId !== updated.project.projectId) {
        throw new Error('Cleanup identities contradict the fixture/project');
      }
      for (const key of ['githubAbsent', 'projectAbsent', 'repositoryLinkAbsent', 'executespecRepositoryAbsent', 'sandboxAbsent', 'runsAbsent', 'deploymentAbsent', 'remoteBranchesAbsent', 'localArtifactsRemoved']) {
        assertBoolean(evidence[key], true, `CLEANUP_VERIFIED.${key}`);
      }
      assertString(evidence.cleanupEvidenceId, 'CLEANUP_VERIFIED.cleanupEvidenceId');
      updated.cleanup.verified = structuredClone(evidence);
      break;
    }
    case 'COMPLETE': {
      assertExactKeys(evidence, ['auditId', 'auditor', 'pass'], [], next);
      assertString(evidence.auditId, 'COMPLETE.auditId');
      assertString(evidence.auditor, 'COMPLETE.auditor');
      assertBoolean(evidence.pass, true, 'COMPLETE.pass');
      updated.cleanup.audit = structuredClone(evidence);
      const result = auditManifestCompleteness(updated);
      updated.cleanup.audit = {
        ...updated.cleanup.audit,
        goEligible: result.goEligible,
        nonPassNegativeCases: result.nonPassNegativeCases,
      };
      break;
    }
    case 'INVALIDATED':
      break;
    default:
      throw new Error(`No evidence contract exists for ${next}`);
  }
  return updated;
}

export function transition(manifest, next, evidence = {}) {
  validateManifest(manifest);
  const allowed = STATE_TRANSITIONS[manifest.state] ?? [];
  if (!allowed.includes(next)) throw new Error(`Illegal litmus transition ${manifest.state} -> ${next}`);
  const beforeDigest = manifest.frozenTupleDigest;
  const updated = materializeEvidence(manifest, next, evidence);
  updated.state = next;
  updated.updatedAt = new Date().toISOString();
  updated.events.push({at: updated.updatedAt, from: manifest.state, to: next, evidence: redactValue(evidence)});
  if (beforeDigest && next !== 'INVALIDATED' && frozenTupleDigest(updated) !== beforeDigest) {
    throw new Error('Frozen tuple changed; invalidate the campaign instead of advancing it');
  }
  return updated;
}

export function auditManifestCompleteness(manifest) {
  assertFrozenTuple(manifest);
  if (new Set([manifest.deployment.expectedSha, manifest.deployment.deployedSha, manifest.deployment.runningSha]).size !== 1) {
    throw new Error('Completion audit: expected/deployed/running SHA mismatch');
  }
  if (!manifest.project.baselineV1 || !manifest.project.baselineV2) throw new Error('Completion audit: both project baselines are required');
  if (manifest.project.baselineV1.sourceSha !== manifest.fixture.baseSha || manifest.project.baselineV2.sourceSha !== manifest.fixture.currentBaseSha) {
    throw new Error('Completion audit: project baselines contradict fixture revisions');
  }
  validateDistinctHeroes(manifest.heroes);
  manifest.heroes.forEach((hero, index) => validateHeroProof(hero, manifest, index + 1));
  assertExactKeys(manifest.postMerge, [
    'heroSubmissions', 'merge', 'refresh', 'continuation', 'ciFailure', 'ciFix', 'deployment',
  ], [], 'postMerge');
  if (!Array.isArray(manifest.postMerge.heroSubmissions) || manifest.postMerge.heroSubmissions.length !== 3) {
    throw new Error('Completion audit: exactly three authoritative hero submissions are required');
  }
  assertExactKeys(manifest.cleanup, ['required', 'verified', 'audit'], ['githubDeletionRequested'], 'cleanup');
  assertExactKeys(manifest.cleanup.audit, ['auditId', 'auditor', 'pass'], ['goEligible', 'nonPassNegativeCases'], 'cleanup.audit');
  if (manifest.postMerge.pendingHero) throw new Error('Completion audit: pending hero remains');
  const merge = manifest.postMerge.merge;
  const refresh = manifest.postMerge.refresh;
  if (!merge || !refresh || refresh.observedMainSha !== merge.mergedSha || refresh.workspaceSha !== merge.mergedSha || refresh.architectureSourceSha !== merge.mergedSha) {
    throw new Error('Completion audit: merged SHA refresh proof is incomplete');
  }
  if (manifest.postMerge.continuation?.sourceSha !== merge.mergedSha || manifest.postMerge.continuation?.status !== 'COMPLETE') {
    throw new Error('Completion audit: continuation proof is incomplete');
  }
  if (!manifest.postMerge.ciFailure || !manifest.postMerge.ciFix
      || manifest.postMerge.ciFailure.prNumber !== manifest.postMerge.ciFix.prNumber
      || manifest.postMerge.ciFailure.testContractSha256 !== manifest.postMerge.ciFix.testContractSha256
      || manifest.postMerge.ciFix.testsWeakened !== false) {
    throw new Error('Completion audit: CI failure/fix proof is incomplete or tests changed');
  }
  if (!['TRACKED', 'SKIPPED'].includes(manifest.postMerge.deployment?.mode)) throw new Error('Completion audit: truthful deployment tracked/skip proof is required');
  if (manifest.negatives.length !== REQUIRED_NEGATIVE_CASES.length || new Set(manifest.negatives.map((item) => item.caseId)).size !== REQUIRED_NEGATIVE_CASES.length) {
    throw new Error('Completion audit: required negative proofs are incomplete');
  }
  for (const item of manifest.negatives) {
    if (NEGATIVE_CASE_REGISTRY[item.caseId].publiclyExecutable && item.status !== 'VERIFIED') {
      throw new Error(`Completion audit: public negative ${item.caseId} was not verified`);
    }
    if (!['VERIFIED', 'NOT_RUN'].includes(item.status)) throw new Error(`Completion audit: invalid negative status for ${item.caseId}`);
  }
  if (!manifest.cleanup.required || !manifest.cleanup.verified || !manifest.cleanup.audit?.pass) throw new Error('Completion audit: cleanup and independent audit proof are required');
  for (const key of ['githubAbsent', 'projectAbsent', 'repositoryLinkAbsent', 'executespecRepositoryAbsent', 'sandboxAbsent', 'runsAbsent', 'deploymentAbsent', 'remoteBranchesAbsent', 'localArtifactsRemoved']) {
    if (manifest.cleanup.verified[key] !== true) throw new Error(`Completion audit: cleanup ${key} is unproven`);
  }
  let replay = structuredClone(manifest);
  replay.state = 'FROZEN';
  replay.heroes = [];
  replay.postMerge = {};
  replay.negatives = [];
  replay.cleanup = {};
  replay.events = [];
  for (let index = 0; index < 3; index += 1) {
    replay = transition(replay, `HERO_${index + 1}_RUNNING`, manifest.postMerge.heroSubmissions[index]);
    replay = transition(replay, `HERO_${index + 1}_VERIFIED`, manifest.heroes[index]);
  }
  replay = transition(replay, 'HERO_PR_MERGED', manifest.postMerge.merge);
  replay = transition(replay, 'MERGED_SHA_REFRESHED', manifest.postMerge.refresh);
  replay = transition(replay, 'CONTINUATION_VERIFIED', manifest.postMerge.continuation);
  replay = transition(replay, 'CI_FAILURE_CREATED', manifest.postMerge.ciFailure);
  replay = transition(replay, 'CI_FIX_VERIFIED', manifest.postMerge.ciFix);
  const {mode: deploymentMode, ...deploymentEvidence} = manifest.postMerge.deployment;
  replay = transition(replay, deploymentMode === 'TRACKED' ? 'DEPLOYMENT_TRACKED' : 'DEPLOYMENT_SKIPPED', deploymentEvidence);
  replay = transition(replay, 'NEGATIVES_VERIFIED', {cases: manifest.negatives});
  replay = transition(replay, 'CLEANUP_REQUIRED', manifest.cleanup.required);
  replay = transition(replay, 'CLEANUP_VERIFIED', manifest.cleanup.verified);
  if (canonicalJson(replay.heroes) !== canonicalJson(manifest.heroes)
      || canonicalJson(replay.postMerge) !== canonicalJson(manifest.postMerge)
      || canonicalJson(replay.negatives) !== canonicalJson(manifest.negatives)
      || canonicalJson(replay.cleanup.required) !== canonicalJson(manifest.cleanup.required)
      || canonicalJson(replay.cleanup.verified) !== canonicalJson(manifest.cleanup.verified)) {
    throw new Error('Completion audit: authoritative evidence does not reproduce the required state sequence');
  }
  const nonPassNegativeCases = manifest.negatives.filter((item) => item.status === 'NOT_RUN').map((item) => item.caseId);
  if ('goEligible' in manifest.cleanup.audit && manifest.cleanup.audit.goEligible !== (nonPassNegativeCases.length === 0)) {
    throw new Error('Completion audit: persisted goEligible contradicts negative evidence');
  }
  if ('nonPassNegativeCases' in manifest.cleanup.audit
      && canonicalJson(manifest.cleanup.audit.nonPassNegativeCases) !== canonicalJson(nonPassNegativeCases)) {
    throw new Error('Completion audit: persisted non-PASS list contradicts negative evidence');
  }
  return {
    pass: true,
    goEligible: nonPassNegativeCases.length === 0,
    campaignId: manifest.campaignId,
    frozenTupleDigest: manifest.frozenTupleDigest,
    nonPassNegativeCases,
  };
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
  assertSha(manifest.deployment.deployedSha, 'deployment.deployedSha');
  assertSha(manifest.deployment.runningSha, 'deployment.runningSha');
  if (new Set([manifest.deployment.expectedSha, manifest.deployment.deployedSha, manifest.deployment.runningSha]).size !== 1) {
    throw new Error('Expected, deployed and running SHA differ');
  }
  assertSha(manifest.fixture.baseSha, 'fixture.baseSha');
  assertSha(manifest.fixture.currentBaseSha, 'fixture.currentBaseSha');
  if (!manifest.fixture.repoFullName || !manifest.project.projectId || !manifest.project.repoId) {
    throw new Error('Fixture and project identities are required before freeze');
  }
  if (!manifest.project.linkEvidenceId || !manifest.project.baselineV1 || !manifest.project.baselineV2) {
    throw new Error('Project link and both baseline evidence sets are required before freeze');
  }
  if (manifest.project.baselineV1.sourceSha !== manifest.fixture.baseSha
      || manifest.project.baselineV2.sourceSha !== manifest.fixture.currentBaseSha) {
    throw new Error('Project baselines must bind the exact fixture revisions');
  }
  if (!manifest.runRequest.requirementSha256 || !manifest.runRequest.canonicalJsonSha256) {
    throw new Error('Frozen run request hashes are required');
  }
  assertDigest(manifest.runRequest.requirementSha256, 'runRequest.requirementSha256');
  assertDigest(manifest.runRequest.canonicalJsonSha256, 'runRequest.canonicalJsonSha256');
  if (sha256(String(manifest.runRequest.userRequest ?? '').trim()) !== manifest.runRequest.requirementSha256) {
    throw new Error('runRequest.requirementSha256 does not bind userRequest');
  }
  const requestBody = structuredClone(manifest.runRequest);
  delete requestBody.requirementSha256;
  delete requestBody.canonicalJsonSha256;
  if (sha256(canonicalJson(requestBody)) !== manifest.runRequest.canonicalJsonSha256) {
    throw new Error('runRequest.canonicalJsonSha256 does not bind the exact request');
  }
  if (manifest.runRequest.executionMode !== 'PRODUCTION' || manifest.runRequest.skipApproval !== false
      || manifest.runRequest.requireConfirmationBeforeExpensiveContinuation !== true) {
    throw new Error('Frozen request must use production, manual approval and confirmed continuation');
  }
  assertPositiveNumber(manifest.runRequest.userCostCap, 'runRequest.userCostCap');
  assertDigest(manifest.approval.decisionContractSha256, 'approval.decisionContractSha256');
  assertSha(manifest.clients.webSha, 'clients.webSha');
  assertSha(manifest.clients.cliSha, 'clients.cliSha');
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
