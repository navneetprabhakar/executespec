#!/usr/bin/env node

import {cp, mkdir, mkdtemp, readFile, rm, stat} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {
  apiRequest,
  canonicalJson,
  freezeManifest,
  invalidateManifest,
  loadManifest,
  newManifest,
  parseArgs,
  requireConfirmation,
  runCommand,
  saveManifest,
  sha256,
  transition,
  validateRoute,
} from './lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const defaultStateRoot = path.join(os.homedir(), '.local', 'state', 'executespec', 'enterprise-litmus');
const {positional, values} = parseArgs(process.argv.slice(2));
const command = positional[0] ?? 'help';
const subcommand = positional[1];
const manifestPath = path.resolve(values.manifest ?? path.join(defaultStateRoot, 'current', 'manifest.json'));

function usage() {
  return `Usage:
  litmus.mjs init [--manifest PATH]
  litmus.mjs status [--manifest PATH]
  litmus.mjs preflight --confirm-live=YES
  litmus.mjs fixture create --confirm-live=YES --confirm-github-mutation=YES
  litmus.mjs fixture cleanup --confirm-live=YES --confirm-delete=OWNER/REPO
  litmus.mjs advance --to STATE --evidence-file PATH --confirm-external-operation-completed=YES
  litmus.mjs freeze
  litmus.mjs invalidate --reason TEXT

Live mutations are never implied by init/status/advance/freeze. Project, paid provider, hero,
merge, CI-fix and deployment operations remain separately orchestrated and must record evidence
through advance; this driver will not auto-spend or auto-deploy.`;
}

async function init() {
  const provider = process.env.EXECUTESPEC_LITMUS_PROVIDER ?? 'DEEPSEEK';
  const model = process.env.EXECUTESPEC_LITMUS_MODEL ?? 'deepseek-v4-pro-native';
  validateRoute(provider, model);
  const requirement = await readFile(path.join(here, 'fixture', 'requirement.txt'), 'utf8');
  const runRequest = {
    userRequest: requirement.trim(),
    executionMode: 'PRODUCTION',
    skipApproval: false,
    tddMode: false,
    aiProvider: provider,
    modelName: model,
    requireConfirmationBeforeExpensiveContinuation: true,
    requireBenchmarkPriorForAutomaticContinuation: false,
    userCostCap: Number(process.env.EXECUTESPEC_LITMUS_COST_CAP_USD ?? 3),
  };
  const manifest = newManifest({
    provider,
    model,
    deployment: {expectedSha: process.env.EXECUTESPEC_LITMUS_DEPLOYED_SHA ?? ''},
    runRequest: {
      ...runRequest,
      requirementSha256: sha256(requirement.trim()),
      canonicalJsonSha256: sha256(canonicalJson(runRequest)),
    },
  });
  await saveManifest(manifestPath, manifest);
  process.stdout.write(`${manifestPath}\n`);
}

async function preflight() {
  requireConfirmation(values, 'confirm-live');
  const manifest = await loadManifest(manifestPath);
  if (manifest.state !== 'NEW') throw new Error('Preflight requires NEW state');
  const baseUrl = process.env.EXECUTESPEC_LITMUS_BASE_URL;
  const token = process.env.EXECUTESPEC_LITMUS_ACCESS_TOKEN;
  const expectedSha = manifest.deployment.expectedSha || process.env.EXECUTESPEC_LITMUS_DEPLOYED_SHA;
  const deployedSha = process.env.EXECUTESPEC_LITMUS_DEPLOYED_SHA;
  const runningSha = process.env.EXECUTESPEC_LITMUS_RUNNING_SHA;
  const deployWorkflowRunId = process.env.EXECUTESPEC_LITMUS_DEPLOY_WORKFLOW_RUN_ID;
  const deployWorkflowRunUrl = process.env.EXECUTESPEC_LITMUS_DEPLOY_WORKFLOW_RUN_URL;
  if (!baseUrl || !token || !expectedSha || !deployedSha || !runningSha || !deployWorkflowRunId || !deployWorkflowRunUrl) {
    throw new Error('Base URL, access token, exact deploy workflow and expected/deployed/RUNNING_SHA evidence are required');
  }
  const [health, readiness] = await Promise.all([
    apiRequest(baseUrl, null, '/actuator/health'),
    apiRequest(baseUrl, token, '/api/v3/provider-readiness'),
    runCommand('gh', ['auth', 'status']),
  ]);
  if (!health.ok || !['UP', 'DEGRADED'].includes(health.body?.status)) throw new Error('Deployment health is not UP/DEGRADED');
  const route = validateRoute(manifest.route.provider, manifest.route.model);
  const row = readiness.body?.providers?.find((item) => item.provider === route.provider);
  if (!readiness.ok || !row?.dispatchable) throw new Error(`${route.provider} is not dispatchable`);
  const updated = transition(manifest, 'PREFLIGHTED', {
    expectedSha,
    deployedSha,
    runningSha,
    runningShaAuthority: 'DEPLOYMENT_HOST:/opt/app/RUNNING_SHA',
    health: health.body.status,
    deployWorkflowRunId,
    deployWorkflowRunUrl,
    deployConclusion: 'SUCCESS',
    providerState: row.state,
    providerPolicyEvidenceId: `sha256:${sha256(JSON.stringify(readiness.body))}`,
    ghAuth: 'VERIFIED',
  });
  await saveManifest(manifestPath, updated);
}

async function fixtureCreate() {
  requireConfirmation(values, 'confirm-live');
  requireConfirmation(values, 'confirm-github-mutation');
  const manifest = await loadManifest(manifestPath);
  if (manifest.state !== 'PREFLIGHTED') throw new Error('Fixture creation requires PREFLIGHTED state');
  const owner = process.env.EXECUTESPEC_LITMUS_GITHUB_OWNER;
  if (!owner || !/^[A-Za-z0-9][A-Za-z0-9-]*$/.test(owner)) throw new Error('A valid EXECUTESPEC_LITMUS_GITHUB_OWNER is required');
  const repo = `${owner}/executespec-litmus-${manifest.campaignId.toLowerCase().replace(/[^a-z0-9-]/g, '-')}`;
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'executespec-litmus-fixture-'));
  try {
    await cp(path.join(here, 'fixture'), temporary, {recursive: true});
    await rm(path.join(temporary, 'requirement.txt'));
    await runCommand('git', ['init', '-b', 'main'], {cwd: temporary});
    await runCommand('git', ['add', '.'], {cwd: temporary});
    await runCommand('git', ['-c', 'user.name=ExecuteSpec Litmus', '-c', 'user.email=litmus@invalid.example', 'commit', '-m', 'fixture: baseline'], {cwd: temporary});
    await runCommand('gh', ['repo', 'create', repo, '--private', '--source', temporary, '--remote', 'origin', '--push']);
    const {stdout: baseSha} = await runCommand('git', ['rev-parse', 'HEAD'], {cwd: temporary});
    const updated = structuredClone(manifest);
    updated.fixture = {...updated.fixture, repoFullName: repo, visibility: 'PRIVATE', baseBranch: 'main', baseSha: baseSha.trim()};
    await saveManifest(manifestPath, transition(updated, 'FIXTURE_CREATED', {
      repoFullName: repo,
      baseBranch: 'main',
      visibility: 'PRIVATE',
      baseSha: baseSha.trim(),
    }));
  } finally {
    await rm(temporary, {recursive: true, force: true});
  }
}

async function fixtureCleanup() {
  requireConfirmation(values, 'confirm-live');
  const manifest = await loadManifest(manifestPath);
  const repo = manifest.fixture?.repoFullName;
  if (!repo || values['confirm-delete'] !== repo) throw new Error(`Refusing delete: --confirm-delete=${repo || 'OWNER/REPO'} is required`);
  await runCommand('gh', ['repo', 'delete', repo, '--yes']);
  const ready = manifest.state === 'CLEANUP_REQUIRED' ? structuredClone(manifest) : transition(manifest, 'CLEANUP_REQUIRED', {
    reason: 'explicit cleanup after litmus evidence collection',
    cleanupPlanId: `github-delete:${manifest.campaignId}`,
  });
  ready.cleanup.githubDeletionRequested = {repoFullName: repo, at: new Date().toISOString()};
  ready.updatedAt = new Date().toISOString();
  await saveManifest(manifestPath, ready);
}

async function readAdvanceEvidence() {
  const evidenceFile = values['evidence-file'];
  if (evidenceFile && values.evidence) throw new Error('Use exactly one of --evidence-file or --evidence');
  if (evidenceFile) {
    const file = path.resolve(evidenceFile);
    const metadata = await stat(file);
    if (!metadata.isFile() || (metadata.mode & 0o077) !== 0 || (metadata.mode & 0o600) !== 0o600) {
      throw new Error('--evidence-file must be a regular file with mode 0600');
    }
    return JSON.parse(await readFile(file, 'utf8'));
  }
  if (!values.evidence) throw new Error('--evidence-file is required (bounded non-secret --evidence is also accepted)');
  if (Buffer.byteLength(values.evidence, 'utf8') > 16_384) throw new Error('Inline --evidence exceeds the 16 KiB non-secret bound; use --evidence-file');
  if (/bearer\s|(?:ghp|github_pat|es_pat)_|api.?key|password|secret/i.test(values.evidence)) {
    throw new Error('Inline --evidence appears to contain a secret; use a sanitized 0600 evidence file');
  }
  return JSON.parse(values.evidence);
}

async function advance() {
  requireConfirmation(values, 'confirm-external-operation-completed');
  const manifest = await loadManifest(manifestPath);
  const next = String(values.to ?? '');
  if (!next) throw new Error('--to is required');
  const evidence = await readAdvanceEvidence();
  if (!evidence || typeof evidence !== 'object' || Array.isArray(evidence) || Object.keys(evidence).length === 0) {
    throw new Error('--evidence must be a non-empty JSON object');
  }
  await saveManifest(manifestPath, transition(manifest, next, evidence));
}

async function main() {
  if (command === 'help' || values.help) return process.stdout.write(`${usage()}\n`);
  if (command === 'init') return init();
  if (command === 'status') return process.stdout.write(`${JSON.stringify(await loadManifest(manifestPath), null, 2)}\n`);
  if (command === 'preflight') return preflight();
  if (command === 'fixture' && subcommand === 'create') return fixtureCreate();
  if (command === 'fixture' && subcommand === 'cleanup') return fixtureCleanup();
  if (command === 'advance') return advance();
  if (command === 'freeze') return saveManifest(manifestPath, freezeManifest(await loadManifest(manifestPath)));
  if (command === 'invalidate') return saveManifest(manifestPath, invalidateManifest(await loadManifest(manifestPath), values.reason));
  throw new Error(`Unknown command\n${usage()}`);
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
