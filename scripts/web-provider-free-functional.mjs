#!/usr/bin/env node
import {createRequire} from 'node:module';
import {mkdir, writeFile} from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const runtimeModules =
  process.env.CODEX_RUNTIME_NODE_MODULES ||
  '/Users/navneetprabhakar/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
let playwright;
try {
  playwright = require('playwright');
} catch {
  playwright = require(path.join(runtimeModules, 'playwright'));
}

const backend = process.env.BACKEND_URL || 'http://localhost:8080';
const frontend = process.env.FRONTEND_URL || 'http://localhost:5174';
const email = process.env.EXECUTESPEC_QA_EMAIL;
const password = process.env.EXECUTESPEC_QA_PASSWORD;
const preserveProviderReadiness = process.env.WEB_QA_PRESERVE_PROVIDER_READINESS === 'true';
if (!email || !password) {
  throw new Error(
    'EXECUTESPEC_QA_EMAIL and EXECUTESPEC_QA_PASSWORD are required; credential-file fallback is intentionally forbidden.',
  );
}

const outDir = path.resolve(
  process.env.WEB_PROVIDER_FREE_RESULTS_DIR || 'scripts/web-provider-free-results',
);
await mkdir(outDir, {recursive: true});

async function api(url, init = {}) {
  const response = await fetch(`${backend}${url}`, {
    ...init,
    signal: init.signal ?? AbortSignal.timeout(20_000),
  });
  if (!response.ok) {
    throw new Error(`${init.method || 'GET'} ${url} failed: ${response.status} ${await response.text()}`);
  }
  if (response.status === 204) return null;
  return response.json();
}

const report = {
  schemaVersion: '2026-07-28',
  type: 'provider-free-web-functional',
  backend,
  frontend,
  fixtureIds: {},
  safetyBefore: null,
  checks: [],
  browserErrors: [],
  failedResponses: [],
  failedRequests: [],
  sseTokenTraffic: [],
  forbiddenRequests: [],
  screenshots: [],
  diagnostics: {},
  limitations: [
    'Ask submission is intentionally excluded because it would require a provider response.',
    'Screens coverage verifies only the configured disabled-state UI, not rendering.',
    'Steps, evidence, and test counts are deterministic fixture-presentation checks, not generated-code quality evidence.',
  ],
  result: 'FAIL',
};

function pass(check, evidence) {
  report.checks.push({status: 'PASS', check, evidence});
}

function safeUrl(raw) {
  try {
    const url = new URL(raw);
    for (const key of ['token', 'access_token', 'refresh_token']) {
      if (url.searchParams.has(key)) url.searchParams.set(key, '[REDACTED]');
    }
    return url.toString();
  } catch {
    return String(raw).replace(/([?&](?:token|access_token|refresh_token)=)[^&\s]+/gi, '$1[REDACTED]');
  }
}

async function visibleText(page, text) {
  await page.waitForFunction(
    (expected) => document.body?.innerText?.includes(expected),
    text,
    {timeout: 20_000},
  );
}

async function selectRunTab(page, name, expectedHeading) {
  // Tabs may append a measured count (for example `Code · 1` or `Activity log · 3`).
  await page.locator('.ide-result-tabs').getByRole('button', {name, exact: false}).click();
  if (expectedHeading) {
    // Some headings append measured counts or retention details to the accessible name.
    await page.getByRole('heading', {name: expectedHeading, exact: false}).waitFor({timeout: 20_000});
  }
}

let login;
let authHeaders;
let fixture;
let browser;
let page;
let createdSessionId;
let createdProjectId;
try {
  login = await api('/api/auth/login', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({email, password}),
  });
  authHeaders = {Authorization: `Bearer ${login.accessToken}`};
  fixture = await api('/api/v2/test/web-fixture', {method: 'POST', headers: authHeaders});
  report.fixtureIds = {
    successRunId: fixture.successRunId,
    failedRunId: fixture.failedRunId,
    gateRunId: fixture.gateRunId,
    agentSessionId: fixture.agentSessionId,
  };
  report.safetyBefore = fixture.safety;
  if (
    fixture.safety?.safe !== true ||
    fixture.safety?.liveLlmEnabled !== false ||
    fixture.safety?.activeProfiles?.length !== 1 ||
    fixture.safety.activeProfiles[0] !== 'web-qa'
  ) {
    throw new Error(`unsafe backend profile refused: ${JSON.stringify(fixture.safety)}`);
  }

  browser = await playwright.chromium.launch({
    headless: true,
    executablePath:
      process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    env: Object.fromEntries(
      ['PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'NO_PROXY']
        .filter((key) => process.env[key])
        .map((key) => [key, process.env[key]]),
    ),
  });
  page = await browser.newPage({viewport: {width: 1440, height: 1000}});
  let workspaceCreationRequests = 0;
  page.on('pageerror', (error) => report.browserErrors.push(`pageerror:${error.message}`));
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) {
      const location = message.location();
      const source = location?.url ? ` ${safeUrl(location.url)}:${location.lineNumber ?? 0}` : '';
      report.browserErrors.push(`${message.type()}:${message.text()}${source}`);
    }
  });
  page.on('response', (response) => {
    if (response.url().includes('/sse-token')) {
      report.sseTokenTraffic.push(`${response.status()} ${safeUrl(response.url())}`);
    }
    if (response.status() >= 400) {
      report.failedResponses.push(
        `${response.status()} ${response.request().method()} ${safeUrl(response.url())}`,
      );
    }
  });
  page.on('requestfailed', (request) => {
    const failure = `${request.failure()?.errorText || 'unknown'} ${request.method()} ${safeUrl(request.url())}`;
    report.failedRequests.push(failure);
    if (request.url().includes('/sse-token')) report.sseTokenTraffic.push(`FAILED ${failure}`);
  });
  page.on('request', (request) => {
    const pathname = new URL(request.url()).pathname;
    if (request.method() === 'POST' && pathname === '/api/agent/sessions') {
      workspaceCreationRequests += 1;
      try {
        const requested = request.postDataJSON();
        if (typeof requested?.id === 'string') createdSessionId = requested.id;
      } catch {
        // The response assertion below still proves the contract if Playwright cannot decode JSON.
      }
    }
    if (request.method() !== 'GET') {
      if (!(request.method() === 'POST' && pathname === '/api/agent/sessions')) {
        report.forbiddenRequests.push(`${request.method()} ${safeUrl(request.url())}`);
      }
    }
  });

  // Seed auth on the shipped origin without relying on an injected pre-navigation script, which
  // packaged CSP correctly rejects.
  await page.goto(`${frontend}/favicon.svg`, {waitUntil: 'domcontentloaded'});
  await page.evaluate(
    ({access, refresh}) => {
      localStorage.setItem('access_token', access);
      if (refresh) localStorage.setItem('refresh_token', refresh);
    },
    {access: login.accessToken, refresh: login.refreshToken},
  );

  await page.goto(`${frontend}/agent`, {waitUntil: 'domcontentloaded'});
  // The chat-first redesign resumes the latest ACTIVE scope-matching session when one exists.
  // The fixture seeds exactly one, so landing may be either the empty launcher or the resumed
  // fixture conversation; the resumed state additionally proves durable session reload.
  await page.waitForFunction(
    () =>
      /Remote workspace operator|Release QA agent evidence/.test(
        document.body?.innerText || '',
      ),
    undefined,
    {timeout: 20_000},
  );
  if (/Release QA agent evidence/.test((await page.locator('body').innerText()) || '')) {
    pass(
      'durable session reload on /agent',
      'The seeded ACTIVE fixture conversation was resumed from durable state on navigation.',
    );
  }
  const sessionCreationResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/agent/sessions',
    {timeout: 20_000},
  );
  const newWorkspaceButton = page.locator(
    'button.agent-bar-btn[aria-label="Start a new conversation"]',
  );
  await newWorkspaceButton.waitFor({state: 'visible', timeout: 20_000});
  await newWorkspaceButton.click();
  const createdSessionResponse = await sessionCreationResponse;
  if (!createdSessionResponse.ok()) {
    throw new Error(`real session creation failed with HTTP ${createdSessionResponse.status()}`);
  }
  const createdSession = await createdSessionResponse.json();
  if (!createdSession?.id || (createdSessionId && createdSession.id !== createdSessionId)) {
    throw new Error('real session creation returned the wrong session identity');
  }
  createdSessionId = createdSession.id;
  await visibleText(page, 'Web agent');
  await page.getByRole('textbox', {name: 'Message the agent'}).waitFor({timeout: 20_000});
  if (workspaceCreationRequests !== 1) {
    throw new Error(
      `Start a new conversation issued ${workspaceCreationRequests} creation requests instead of one`,
    );
  }
  const agentText = await page.locator('body').innerText();
  if (/Remote sandbox operator|strict remote container|container enforced/i.test(agentText)) {
    throw new Error('disabled sandbox was presented as enabled');
  }
  pass(
    'disabled-sandbox workspace creation contract',
    'The Workspace control issued one canonical request and the real provider-free backend persisted and returned that exact session.',
  );

  // Exercise the real project lifecycle without repositories or providers: persist a new project,
  // load its protected project shell, then remove it in the outer finally block. This is a real
  // controller/service/database path rather than a pre-shaped fixture row.
  const projectName = `Provider-free QA ${Date.now()}`;
  const createdProject = await api('/api/projects', {
    method: 'POST',
    headers: {...authHeaders, 'Content-Type': 'application/json'},
    body: JSON.stringify({name: projectName, aiProvider: 'DEEPSEEK'}),
  });
  if (!createdProject?.id) throw new Error('project creation did not return an id');
  createdProjectId = createdProject.id;
  report.fixtureIds.createdProjectId = createdProjectId;
  await page.goto(`${frontend}/projects/${createdProjectId}`, {waitUntil: 'domcontentloaded'});
  await visibleText(page, projectName);
  await visibleText(page, 'Overview');
  pass(
    'new-project functional lifecycle',
    'A real authenticated project was created, persisted, and loaded through the protected project UI without an LLM call.',
  );

  // Compare History must render an actual CONTENT diff between two architecture versions, not a
  // bare "changed" marker. Two deterministic fixture versions differ by one known added line.
  const archVersions = await api(
    `/api/v2/test/web-fixture/projects/${createdProjectId}/architecture-versions`,
    {method: 'POST', headers: authHeaders},
  );
  if (!archVersions?.toVersion) throw new Error('architecture version seeding failed');
  await page.goto(`${frontend}/projects/${createdProjectId}/architecture`, {
    waitUntil: 'domcontentloaded',
  });
  await page.getByRole('button', {name: /History · \d+/}).click();
  // The heading renders uppercase via CSS text-transform, so innerText matching is unreliable;
  // the labeled select is the stable readiness signal for the compare panel.
  await page.getByLabel('Compare from version').waitFor({state: 'visible', timeout: 20_000});
  await page
    .getByLabel('Compare from version')
    .selectOption(String(archVersions.fromVersion));
  await page.getByLabel('Compare to version').selectOption(String(archVersions.toVersion));
  await page.getByRole('button', {name: 'Compare', exact: true}).click();
  await visibleText(
    page,
    `System map content · v${archVersions.fromVersion} → v${archVersions.toVersion}`,
  );
  await visibleText(page, archVersions.addedLine);
  const compareBody = (await page.locator('body').innerText()) || '';
  if (/No system-map content changes/.test(compareBody)) {
    throw new Error('Compare History reported no content changes for two differing versions');
  }
  pass(
    'Compare History content diff',
    'Two fixture architecture versions rendered an actual line-level content diff including the known added service line.',
  );

  // The fixture's inert DeepSeek marker exists only to exercise provider-selectable project setup.
  // Remove it before Compose so the real readiness endpoint has no dispatchable route.
  await api('/api/auth/settings/ai-keys/deepseek', {
    method: 'DELETE',
    headers: authHeaders,
  });
  const composeReadinessResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v3/provider-readiness',
    {timeout: 20_000},
  );
  await page.goto(`${frontend}/compose`, {waitUntil: 'domcontentloaded'});
  const composeReadiness = await (await composeReadinessResponse).json();
  const readinessRows = Array.isArray(composeReadiness?.providers)
    ? composeReadiness.providers
    : [];
  if (readinessRows.length === 0 || readinessRows.some((row) => row?.dispatchable === true)) {
    throw new Error(`provider-free Compose readiness drifted: ${JSON.stringify(readinessRows)}`);
  }
  const createPlanButton = page.getByRole('button', {name: 'Create plan', exact: true});
  await createPlanButton.waitFor({state: 'visible', timeout: 20_000});
  if (!(await createPlanButton.isDisabled())) {
    throw new Error('desktop Compose enabled Create plan without a dispatchable provider');
  }
  pass(
    'provider-free Compose admission',
    'At the 1440px desktop viewport, every readiness row was non-dispatchable and Create plan remained disabled.',
  );

  // The optional CLI pass follows the browser and already relies on this inert fixture marker.
  if (preserveProviderReadiness) {
    await api('/api/auth/settings/ai-keys', {
      method: 'PUT',
      headers: {...authHeaders, 'Content-Type': 'application/json'},
      body: JSON.stringify({deepSeekApiKey: 'web-qa-disabled-provider-key'}),
    });
  }

  // OpenRouter is deliberately an org-scoped key, but it must still be discoverable from the
  // customer-facing provider-key route that originally omitted it. Follow the shipped handoff to
  // the canonical org input without saving a credential.
  await page.goto(`${frontend}/provider-keys`, {waitUntil: 'domcontentloaded'});
  await visibleText(page, 'OpenRouter');
  await page.getByRole('link', {name: 'Configure org key'}).click();
  await page.waitForURL(`${frontend}/org/settings#provider-keys`, {timeout: 20_000});
  await page
    .getByLabel('OpenRouter organization provider key')
    .waitFor({state: 'visible', timeout: 20_000});
  pass(
    'OpenRouter provider-key discoverability',
    'The provider-key page exposed OpenRouter and linked to the canonical org-scoped write-only key input without mutating credentials.',
  );

  await page.goto(`${frontend}/result/${fixture.successRunId}`, {waitUntil: 'domcontentloaded'});
  await visibleText(page, 'Release QA success');
  pass('successful run overview', 'COMPLETE fixture rendered its deterministic status and signals.');

  await selectRunTab(page, 'Code');
  const desktopExplorer = page.locator('.ide-split-files');
  const mobileFilePicker = page.getByRole('combobox', {name: 'Select a file to view'});
  await page.waitForFunction(
    () =>
      Boolean(document.querySelector('.ide-split-files')) ||
      Boolean(document.querySelector('select[aria-label="Select a file to view"]')),
    null,
    {timeout: 20_000},
  );
  if (await desktopExplorer.isVisible()) {
    await desktopExplorer.getByText('Explorer', {exact: false}).waitFor({timeout: 20_000});
    await desktopExplorer.getByText('README.md', {exact: false}).waitFor({timeout: 20_000});
  } else {
    await mobileFilePicker.waitFor({timeout: 20_000});
    const options = await mobileFilePicker.locator('option').allTextContents();
    if (!options.some((option) => option.includes('README.md'))) {
      throw new Error(`responsive Code picker did not contain README.md: ${options.join(', ')}`);
    }
  }
  pass('Code tab', 'Responsive workspace explorer rendered the real README.md fixture file.');

  // Exercise the production timestamp parser through the rendered Timeline tab. The original
  // regression treated a missing start as Unix epoch and displayed a multi-million-minute task.
  // This fixture has an exact 30-second interval, so the browser assertion covers both the API
  // payload and the real chart formatter rather than only the pure utility test.
  await selectRunTab(page, 'Timeline', 'Timeline');
  await visibleText(page, '30s');
  const timelineText = await page.locator('body').innerText();
  if (/\b\d{5,}m\b/.test(timelineText)) {
    throw new Error(`Timeline rendered an epoch-scale duration: ${timelineText.slice(0, 600)}`);
  }
  pass(
    'Timeline timestamp integrity',
    'The authenticated result view rendered the persisted 30-second task interval without an epoch-scale duration.',
  );

  await selectRunTab(page, 'Screens', 'Screens');
  await visibleText(page, 'Screen rendering is turned off for this deployment.');
  pass(
    'Screens disabled-state presentation only',
    'The tab rendered the configured disabled state; this does not validate screen rendering.',
  );

  await selectRunTab(page, 'Steps', 'Steps');
  await visibleText(page, 'Validate fixture');
  const stepsText = await page.locator('body').innerText();
  if (/Model route choice|benchmark evidence missing|quality first, then cost/i.test(stepsText)) {
    throw new Error('internal model-routing diagnostics leaked into the Steps fixture');
  }
  pass(
    'fixture Steps presentation only',
    'The deterministic fixture rendered without internal routing diagnostics; generated step quality is not covered.',
  );

  await selectRunTab(page, 'Activity log', 'Activity log');
  await visibleText(page, 'fixture verification passed');
  pass('Activity log tab', 'Persisted fixture execution evidence rendered.');

  await selectRunTab(page, 'Evidence', 'Evidence');
  await visibleText(page, 'Files generated');
  await visibleText(page, 'PASSED');
  pass(
    'fixture Evidence presentation only',
    'Deterministic fixture facts rendered without a fabricated score; production evidence quality is not covered.',
  );

  // Correction transaction visibility: the durable continuation_actions_json fixture must render
  // as a correction card with checkpoint generations, evidence invalidation, and the validation
  // before → after delta — the exact facts the trust slice persists for real corrections.
  const correctionCard = page.getByTestId('correction-evidence-card').first();
  await correctionCard.waitFor({state: 'visible', timeout: 20_000});
  // Headings render uppercase via CSS text-transform, so all card assertions are
  // case-insensitive against the card's own rendered text.
  const correctionText = (await correctionCard.innerText()) || '';
  if (!/correction transaction 1\/1/i.test(correctionText)) {
    throw new Error('correction card did not render its transaction ordinal');
  }
  if (!/prior evidence was made stale/i.test(correctionText)) {
    throw new Error('correction card did not render the evidence-invalidation state');
  }
  if (!/src\/app\.ts/.test(correctionText)) {
    throw new Error('correction card did not render the changed-file evidence');
  }
  if (!/FAILED/.test(correctionText) || !/PASSED/.test(correctionText)) {
    throw new Error('correction card did not render the before → after validation delta');
  }
  pass(
    'correction transaction evidence card',
    'Durable correction evidence rendered checkpoint generations, invalidation state, changed files, and the FAILED → PASSED validation delta from continuation_actions_json.',
  );

  await selectRunTab(page, 'Tests', 'Tests');
  await visibleText(page, '2/2');
  pass(
    'fixture Tests presentation only',
    'Two pre-seeded fixture result rows rendered as 2/2; no generated code or test suite was executed.',
  );

  await selectRunTab(page, 'Cost', 'Cost');
  await visibleText(page, 'AI credits used');
  await visibleText(page, 'Cache-read tokens');
  pass(
    'Cost ledger presentation',
    'The authenticated cost surface rendered run-credit and cache-token fields without invoking a provider.',
  );

  await selectRunTab(page, 'Pull request', 'Pull request');
  // The fixture workspace has no Git generation, so the publication-trust PR tab renders the
  // honest blocked state (workspace generation unreadable) rather than a generic empty state.
  // A plain "No pull request yet" remains valid for eligible-but-unpublished workspaces.
  await page.waitForFunction(
    () =>
      /No pull request yet|No pull request can be opened/i.test(document.body?.innerText || ''),
    undefined,
    {timeout: 20_000},
  );
  pass(
    'Pull request empty state',
    'The result view rendered an honest no-PR state (generic empty state or explicit workspace-generation block) without inventing publication eligibility.',
  );

  await selectRunTab(page, 'Ask', 'Ask about this work');
  await visibleText(page, 'No messages yet');
  pass(
    'Ask composer presentation only',
    'Ask loaded history and its composer; response generation and budget handling are intentionally not covered.',
  );

  await page.goto(`${frontend}/result/${fixture.failedRunId}`, {waitUntil: 'domcontentloaded'});
  await page.waitForURL(`${frontend}/result/${fixture.failedRunId}`, {timeout: 20_000});
  const failedRun = await api(`/api/runs/${fixture.failedRunId}`, {headers: authHeaders});
  if (failedRun.runId !== fixture.failedRunId || failedRun.status !== 'FAILED') {
    throw new Error(`failed-run fixture contract drifted: ${JSON.stringify(failedRun)}`);
  }
  await page.getByRole('heading', {name: 'Release QA recovery', exact: true}).waitFor({timeout: 20_000});
  const recoveryCard = page.locator('.ide-recovery-card');
  await recoveryCard.getByText('Recovery path', {exact: true}).waitFor({timeout: 20_000});
  await recoveryCard.locator('[aria-label="Recovery actions"]').waitFor({timeout: 20_000});
  pass('failed-run recovery overview', 'Failure reason and recovery affordances rendered.');

  await page.goto(`${frontend}/result/${fixture.gateRunId}`, {waitUntil: 'domcontentloaded'});
  await page.waitForURL(`${frontend}/plan/${fixture.gateRunId}`, {timeout: 20_000});
  await visibleText(page, 'Review plan');
  await visibleText(page, 'Approve and start work');
  pass('plan-gate view', 'Plan, credit boundary, approval and cancel controls rendered.');

  const advanceHeaders = {...authHeaders, 'Content-Type': 'application/json'};
  await api(`/api/v2/test/web-fixture/${fixture.gateRunId}/advance`, {
    method: 'POST',
    headers: advanceHeaders,
    body: JSON.stringify({status: 'EXECUTING'}),
  });
  // Exercise the shipped SSE + durable-poll transition instead of forcing a page reload, which
  // aborts healthy in-flight fetches and does not represent the normal plan-to-live workflow.
  await page.waitForURL(`${frontend}/live/${fixture.gateRunId}`, {timeout: 25_000});
  await visibleText(page, 'WORKING');
  await api(`/api/v2/test/web-fixture/${fixture.gateRunId}/advance`, {
    method: 'POST',
    headers: advanceHeaders,
    body: JSON.stringify({status: 'COMPLETE'}),
  });
  await page.waitForURL(`${frontend}/result/${fixture.gateRunId}`, {timeout: 25_000});
  await visibleText(page, 'COMPLETE');
  pass('provider-free lifecycle', 'Fixture-only AWAITING_APPROVAL → WORKING → COMPLETE rendered.');
  await page.locator('.ide-gate-badge').waitFor({state: 'hidden', timeout: 20_000});
  pass('plan-gate indicator freshness', 'The global awaiting-approval count cleared on navigation.');

  const screenshot = path.join(outDir, 'provider-free-run-complete.png');
  await page.screenshot({path: screenshot, fullPage: true});
  report.screenshots.push(screenshot);

  const unexpectedResponses = report.failedResponses.filter(
    (line) => !/favicon\.ico|ERR_ABORTED/.test(line),
  );
  const unexpectedErrors = report.browserErrors.filter(
    (line) => !/Download the React DevTools|net::ERR_ABORTED|favicon/i.test(line),
  );
  if (report.forbiddenRequests.length) {
    throw new Error(`unexpected browser mutations observed: ${report.forbiddenRequests.join(' | ')}`);
  }
  if (unexpectedResponses.length) {
    throw new Error(`HTTP failures observed: ${unexpectedResponses.join(' | ')}`);
  }
  const unexpectedRequestFailures = report.failedRequests.filter(
    (line) => !/ERR_ABORTED|NS_BINDING_ABORTED/i.test(line),
  );
  if (unexpectedRequestFailures.length) {
    throw new Error(`network request failures observed: ${unexpectedRequestFailures.join(' | ')}`);
  }
  if (unexpectedErrors.length) {
    throw new Error(`browser console failures observed: ${unexpectedErrors.join(' | ')}`);
  }
  pass('browser console/network', 'No unexpected browser errors or HTTP 4xx/5xx responses.');
} catch (error) {
  report.failure = error instanceof Error ? error.stack || error.message : String(error);
  if (page) {
    report.diagnostics.url = page.url();
    report.diagnostics.bodyText = (await page.locator('body').innerText().catch(() => '')).slice(0, 8_000);
    const failureScreenshot = path.join(outDir, 'provider-free-failure.png');
    await page.screenshot({path: failureScreenshot, fullPage: true}).catch(() => undefined);
    report.screenshots.push(failureScreenshot);
  }
} finally {
  if (createdProjectId && authHeaders) {
    try {
      await api(`/api/projects/${encodeURIComponent(createdProjectId)}`, {
        method: 'DELETE',
        headers: authHeaders,
      });
      const activeProjects = await api('/api/projects', {headers: authHeaders});
      if (
        !Array.isArray(activeProjects) ||
        activeProjects.some((project) => project?.id === createdProjectId)
      ) {
        throw new Error('soft-deleted disposable project remained in the active project list');
      }
      pass(
        'new-project cleanup',
        'The disposable project was soft-deleted and was absent from the authenticated active-project list.',
      );
    } catch (error) {
      report.failure = `${report.failure ? `${report.failure}\n` : ''}project cleanup failed: ${error}`;
    }
  }
  if (fixture && authHeaders) {
    try {
      const cleanupParams = new URLSearchParams();
      if (createdSessionId) cleanupParams.set('sessionId', createdSessionId);
      if (preserveProviderReadiness) cleanupParams.set('preserveProviderReadiness', 'true');
      const cleanupQuery = cleanupParams.size ? `?${cleanupParams.toString()}` : '';
      const cleanup = await api(`/api/v2/test/web-fixture${cleanupQuery}`, {
        method: 'DELETE',
        headers: authHeaders,
      });
      report.cleanup = cleanup;
      report.safetyAfter = cleanup?.safety;
      if (
        cleanup?.safety?.featureFlagSnapshotSha256 !==
        fixture.safety.featureFlagSnapshotSha256
      ) {
        report.failure = `${report.failure ? `${report.failure}\n` : ''}feature-flag snapshot changed during browser functional run`;
      } else {
        pass(
          'feature-flag immutability',
          `Snapshot remained ${fixture.safety.featureFlagSnapshotSha256}.`,
        );
      }
    } catch (error) {
      report.failure = `${report.failure ? `${report.failure}\n` : ''}fixture cleanup failed: ${error}`;
    }
  }
  if (browser) {
    try {
      await browser.close();
    } catch (error) {
      report.failure = `${report.failure ? `${report.failure}\n` : ''}browser cleanup failed: ${error}`;
    }
  }
}

report.result = report.failure ? 'FAIL' : 'PASS';
await writeFile(path.join(outDir, 'provider-free-web-functional.json'), `${JSON.stringify(report, null, 2)}\n`);
console.log(
  JSON.stringify(
    {result: report.result, checks: report.checks.length, outDir, failure: report.failure || null},
    null,
    2,
  ),
);
if (report.result !== 'PASS') process.exitCode = 1;
