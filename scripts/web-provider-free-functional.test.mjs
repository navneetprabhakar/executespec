import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const browser = await readFile(new URL('./web-provider-free-functional.mjs', import.meta.url), 'utf8');
const launcher = await readFile(
  new URL('./run-web-provider-free-functional.sh', import.meta.url),
  'utf8',
);

test('browser functional harness is fail-closed to the provider-free web-qa profile', () => {
  assert.match(browser, /fixture\.safety\?\.safe !== true/);
  assert.match(browser, /fixture\.safety\?\.liveLlmEnabled !== false/);
  assert.match(browser, /fixture\.safety\.activeProfiles\[0\] !== 'web-qa'/);
  assert.match(browser, /featureFlagSnapshotSha256/);
});

test('browser functional harness has no credential-file fallback and labels exclusions honestly', () => {
  assert.doesNotMatch(browser, /dev_creds|local_creds|readFile\(/);
  assert.match(browser, /credential-file fallback is intentionally forbidden/);
  assert.match(browser, /Ask submission is intentionally excluded/i);
  assert.match(browser, /response generation and budget handling are intentionally not covered/i);
  assert.match(browser, /this does not validate screen rendering/i);
  assert.match(browser, /generated step quality is not covered/i);
  assert.doesNotMatch(browser, /sendChat|\/api\/runs\/\$\{[^}]+\}\/chat/);
  assert.match(browser, /getByRole\('textbox', \{name: 'Message the agent'\}\)/);
  assert.match(browser, /url\.searchParams\.set\(key, '\[REDACTED\]'\)/);
  assert.match(browser, /safeUrl\(request\.url\(\)\)/);
});

test('workspace creation reaches the real backend and its session is included in cleanup', () => {
  assert.doesNotMatch(browser, /route\.fulfill/);
  assert.match(browser, /page\.waitForResponse/);
  assert.match(browser, /real session creation failed/);
  assert.match(browser, /cleanupParams\.set\('sessionId', createdSessionId\)/);
  assert.match(browser, /cleanupParams\.set\('preserveProviderReadiness', 'true'\)/);
});

test('project setup uses a real disposable project and guarantees cleanup', () => {
  assert.match(browser, /const createdProject = await api\('\/api\/projects'/);
  assert.match(browser, /new-project functional lifecycle/);
  assert.match(browser, /page\.goto\(`\$\{frontend\}\/projects\/\$\{createdProjectId\}`/);
  assert.match(browser, /api\(`\/api\/projects\/\$\{encodeURIComponent\(createdProjectId\)\}`/);
  assert.match(browser, /const activeProjects = await api\('\/api\/projects'/);
  assert.match(browser, /project\?\.id === createdProjectId/);
  assert.match(browser, /new-project cleanup/);
});

test('OpenRouter is discoverable from provider keys without writing a credential', () => {
  assert.match(browser, /page\.goto\(`\$\{frontend\}\/provider-keys`/);
  assert.match(browser, /getByRole\('link', \{name: 'Configure org key'\}\)/);
  assert.match(browser, /getByLabel\('OpenRouter organization provider key'\)/);
  assert.match(browser, /OpenRouter provider-key discoverability/);
});

test('run transitions are identity-scoped and leave failure diagnostics', () => {
  assert.match(browser, /failedRun\.runId !== fixture\.failedRunId/);
  assert.match(browser, /failedRun\.status !== 'FAILED'/);
  assert.match(browser, /getByRole\('heading', \{name: 'Release QA recovery', exact: true\}\)/);
  assert.match(browser, /locator\('\.ide-recovery-card'\)/);
  assert.match(browser, /waitForURL\(`\$\{frontend\}\/plan\/\$\{fixture\.gateRunId\}`/);
  assert.match(browser, /waitForURL\(`\$\{frontend\}\/live\/\$\{fixture\.gateRunId\}`/);
  assert.match(browser, /locator\('\.ide-gate-badge'\)\.waitFor\(\{state: 'hidden'/);
  assert.match(browser, /provider-free-failure\.png/);
  assert.match(browser, /report\.diagnostics\.bodyText/);
});

test('launcher uses disposable services and allowlisted process environments', () => {
  assert.match(launcher, /SPRING_PROFILES_ACTIVE=web-qa/);
  // V95 installs the vector extension, so a plain PostgreSQL image cannot boot a clean schema.
  assert.match(launcher, /pgvector\/pgvector:pg16/);
  assert.match(launcher, /redis:7-alpine/);
  assert.match(launcher, /APP_CORS_ALLOWED_ORIGINS=/);
  assert.match(launcher, /UPDATE org SET tier='ENTERPRISE'/);
  assert.match(launcher, /--strictPort/);
  assert.match(launcher, /env -i/);
  assert.match(launcher, /EXECUTESPEC_NO_UPDATE_CHECK=1/);
  assert.match(launcher, /EXECUTESPEC_ENABLE_LIVE_WEB=0/);
  assert.match(launcher, /WEB_QA_PRESERVE_PROVIDER_READINESS=/);
  assert.doesNotMatch(launcher, /OPENAI_API_KEY|ANTHROPIC_API_KEY|GOOGLE_API_KEY/);
  assert.doesNotMatch(launcher, /dev_creds|local_creds|Desktop/);
  assert.match(launcher, /WEB_QA_CLIENT_ROOT/);
  assert.match(launcher, /EXECUTESPEC_QA_SUPERADMIN_EMAIL/);
  assert.match(launcher, /EXECUTESPEC_QA_SUPERADMIN_PASSWORD/);
  assert.match(launcher, /npm run qa:cli-behavior/);
});
