#!/usr/bin/env node
import {createRequire} from 'node:module';
import {createServer} from 'node:net';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const outDir = path.join(root, 'scripts', 'web-agent-browser-smoke-results');
const backendUrl = process.env.BASE_URL || process.env.BACKEND_URL || 'http://localhost:8080';
const chromePath =
  process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const runtimeNodeModules =
  process.env.CODEX_RUNTIME_NODE_MODULES ||
  '/Users/navneetprabhakar/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';

function loadPlaywright() {
  try {
    return require('playwright');
  } catch {
    return require(path.join(runtimeNodeModules, 'playwright'));
  }
}

async function isPortFree(port) {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.on('error', (error) => {
      if (error.code === 'EADDRINUSE') resolve(false);
      else reject(error);
    });
    server.listen(port, 'localhost', () => {
      server.close(() => resolve(true));
    });
  });
}

async function findFrontendPort() {
  const explicit = process.env.FRONTEND_PORT ? Number(process.env.FRONTEND_PORT) : null;
  const candidates = explicit ? [explicit] : [5173, 5174];
  for (const port of candidates) {
    if (await isPortFree(port)) return port;
  }
  throw new Error(
    `No allowed frontend smoke port is free (${candidates.join(', ')}). Backend CORS only allows the normal local dev origins.`,
  );
}

async function waitFor(url, label, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = '';
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url);
      if (res.ok) return res;
      lastError = `HTTP ${res.status}`;
    } catch (e) {
      lastError = e instanceof Error ? e.message : String(e);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`${label} was not ready at ${url}: ${lastError}`);
}

async function readLocalCredentials() {
  const email = process.env.EMAIL || process.env.EXECUTESPEC_SMOKE_EMAIL;
  const password = process.env.PASSWORD || process.env.EXECUTESPEC_SMOKE_PASSWORD;
  if (email && password) return {email, password};

  const credsPath =
    process.env.LOCAL_CREDS_FILE || '/Users/navneetprabhakar/Desktop/local_creds.txt';
  const text = await readFile(credsPath, 'utf8');
  const line = text
    .split(/\r?\n/)
    .find((row) => row.startsWith('revalidate-ent@test.local'));
  if (!line) throw new Error(`Enterprise local credential row not found in ${credsPath}`);
  const [account, pass] = line.split(/\s+/);
  return {email: account, password: pass};
}

async function login() {
  const {email, password} = await readLocalCredentials();
  const res = await fetch(`${backendUrl}/api/auth/login`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({email, password}),
  });
  if (!res.ok) {
    throw new Error(`Login failed: HTTP ${res.status} ${await res.text()}`);
  }
  const json = await res.json();
  if (!json.accessToken) throw new Error('Login response did not include accessToken.');
  return {email, token: json.accessToken};
}

/**
 * Create an isolated, empty WEB session for this smoke. The local credential used by release
 * QA is intentionally long-lived and may contain historical fixture turns that reference runs
 * deleted by a previous attempt. Selecting a fresh session keeps this shell/contract smoke
 * side-effect free with respect to runs while still exercising the real authenticated API.
 */
async function createSmokeSession(auth) {
  const id = `web-browser-smoke-${crypto.randomUUID()}`;
  const res = await fetch(`${backendUrl}/api/agent/sessions`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${auth.token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      id,
      surface: 'WEB',
      title: `Browser smoke ${id.slice(-12)}`,
      defaultExecutionMode: 'PRODUCTION',
      approvalPolicy: 'CONFIRM_MUTATIONS',
    }),
  });
  if (!res.ok) {
    throw new Error(`Failed to create isolated browser smoke session: HTTP ${res.status} ${await res.text()}`);
  }
  const json = await res.json();
  if (json.id !== id) {
    throw new Error(`Browser smoke session id mismatch: expected ${id}, received ${json.id || 'none'}`);
  }
  return id;
}

function startVite(port) {
  const child = spawn(
    'npm',
    ['--prefix', 'frontend', 'run', 'dev', '--', '--host', 'localhost', '--port', String(port)],
    {
      cwd: root,
      env: {...process.env, VITE_BACKEND_URL: backendUrl},
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  let output = '';
  child.stdout.on('data', (chunk) => {
    output += chunk.toString();
  });
  child.stderr.on('data', (chunk) => {
    output += chunk.toString();
  });
  return {child, getOutput: () => output};
}

async function main() {
  await mkdir(outDir, {recursive: true});
  await waitFor(`${backendUrl}/actuator/health`, 'backend health');
  const auth = await login();
  const smokeSessionId = await createSmokeSession(auth);
  const port = await findFrontendPort();
  const frontendUrl = `http://localhost:${port}`;
  const vite = startVite(port);
  const timestamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, '');
  const screenshot = path.join(outDir, `web-agent-browser-smoke-${timestamp}.png`);
  const report = path.join(outDir, `web-agent-browser-smoke-${timestamp}.md`);
  const results = [];
  const consoleMessages = [];
  const failedResponses = [];
  const requestFailures = [];
  const pageErrors = [];
  let page;
  let browser;

  try {
    await waitFor(frontendUrl, 'vite frontend');
    const {chromium} = loadPlaywright();
    browser = await chromium.launch({
      headless: true,
      executablePath: existsSync(chromePath) ? chromePath : undefined,
    });
    page = await browser.newPage({viewport: {width: 1440, height: 1000}});
    // The account is deliberately reusable across QA runs. Force the newly-created empty session
    // to the head of the real API response so stale historical fixture turns cannot hydrate a
    // deleted run and turn this provider-free shell smoke into an accidental run/SSE test.
    await page.route('**/api/agent/sessions*', async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue();
        return;
      }
      const response = await route.fetch();
      const rows = await response.json().catch(() => null);
      if (!Array.isArray(rows)) {
        await route.fulfill({response});
        return;
      }
      const ordered = [...rows].sort((a, b) => {
        if (a?.id === smokeSessionId) return -1;
        if (b?.id === smokeSessionId) return 1;
        return 0;
      });
      await route.fulfill({response, body: JSON.stringify(ordered)});
    });
    page.on('console', (msg) => {
      if (['error', 'warning'].includes(msg.type())) {
        consoleMessages.push(`${msg.type()}: ${msg.text()}`);
      }
    });
    page.on('pageerror', (error) => {
      pageErrors.push(error.stack || error.message);
    });
    page.on('requestfailed', (request) => {
      requestFailures.push(`${request.failure()?.errorText || 'failed'} ${request.url()}`);
    });
    page.on('response', async (response) => {
      if (response.status() >= 400) {
        let body = '';
        if (/\/api\/agent|\/api\/orchestrator|\/api\/runs/.test(response.url())) {
          body = await response.text().catch(() => '');
        }
        failedResponses.push(`${response.status()} ${response.url()}${body ? ` :: ${body.slice(0, 500)}` : ''}`);
      }
    });
    await page.addInitScript((token) => {
      localStorage.setItem('access_token', token);
    }, auth.token);

    await page.goto(`${frontendUrl}/agent`, {waitUntil: 'networkidle'});
    // The agent surface is now the chat-first "Remote sandbox operator" contract. Keep this
    // smoke deterministic and side-effect free: semantic turn/run acceptance is covered by the
    // dedicated authenticated scenario harness, while this check proves the protected shell and
    // WEB/local-hands boundary without spending an LLM call.
    await page.getByText('Remote sandbox operator').first().waitFor({timeout: 20_000});
    await page.getByText('Ready', {exact: true}).waitFor({timeout: 20_000});
    await page.getByText('No token usage yet', {exact: true}).waitFor({timeout: 20_000});
    await page.getByText('Remote sandbox only', {exact: true}).waitFor({timeout: 20_000});
    await page.getByRole('button', {name: 'Start a new sandbox'}).waitFor({timeout: 20_000});
    results.push([
      'PASS',
      'agent shell and sandbox contract',
      'Remote sandbox operator, Ready state, usage rail, composer contract, and Start a new sandbox rendered',
    ]);

    const pageText = await page.locator('body').innerText();
    if (/\b(?:local_code_task|local_git_|LOCAL_TOOL_ACTION)\b/i.test(pageText)) {
      throw new Error(`WEB surface leaked local-only action names: ${pageText.slice(0, 1200)}`);
    }
    results.push([
      'PASS',
      'WEB capability boundary',
      'Protected empty state contains no local tool/action capability names',
    ]);

    await page.screenshot({path: screenshot, fullPage: true});
    results.push(['PASS', 'screenshot', screenshot]);

    const noisyConsole = consoleMessages.filter(
      (line) => !/Download the React DevTools|favicon/i.test(line),
    );
    if (noisyConsole.length) {
      throw new Error(`Browser console warnings/errors: ${noisyConsole.join(' | ')}`);
    }
    const relevantFailures = failedResponses.filter(
      (line) => !/\/favicon\.ico|chrome-extension:/.test(line),
    );
    if (relevantFailures.length) {
      throw new Error(`HTTP failures observed: ${relevantFailures.join(' | ')}`);
    }
    results.push(['PASS', 'browser console/network', 'No console errors/warnings or HTTP 4xx/5xx']);

    const rows = results
      .map(([status, check, observed]) => `| ${status} | ${check} | ${observed} |`)
      .join('\n');
    await writeFile(
      report,
      `# Web Agent Browser Smoke - ${timestamp}

- Backend: \`${backendUrl}\`
- Frontend: \`${frontendUrl}\`
- Account: \`${auth.email}\`
- Isolated session: \`${smokeSessionId}\`
- Screenshot: \`${screenshot}\`
- Summary: ${results.length} pass, 0 fail

| Status | Check | Observed |
| --- | --- | --- |
${rows}
`,
    );
    console.log(`Report: ${report}`);
  } catch (e) {
    const message = e instanceof Error ? e.stack || e.message : String(e);
    const failureScreenshot = screenshot.replace(/\.png$/, '-failure.png');
    let pageText = '';
    if (page) {
      await page.screenshot({path: failureScreenshot, fullPage: true}).catch(() => {});
      pageText = await page.locator('body').innerText({timeout: 2_000}).catch(() => '');
    }
    const rows = results
      .map(([status, check, observed]) => `| ${status} | ${check} | ${observed} |`)
      .join('\n');
    await writeFile(
      report,
      `# Web Agent Browser Smoke - ${timestamp}

- Backend: \`${backendUrl}\`
- Frontend: \`${frontendUrl}\`
- Account: \`${auth.email}\`
- Isolated session: \`${smokeSessionId}\`
- Failure screenshot: \`${failureScreenshot}\`
- Summary: FAILED
- Console warnings/errors: ${consoleMessages.length ? consoleMessages.map((line) => `\`${line.replace(/`/g, "'")}\``).join(', ') : 'none'}
- Page errors: ${pageErrors.length ? pageErrors.map((line) => `\`${line.replace(/`/g, "'").slice(0, 500)}\``).join(', ') : 'none'}
- Request failures: ${requestFailures.length ? requestFailures.map((line) => `\`${line.replace(/`/g, "'").slice(0, 500)}\``).join(', ') : 'none'}
- HTTP failures: ${failedResponses.length ? failedResponses.map((line) => `\`${line.replace(/`/g, "'").slice(0, 500)}\``).join(', ') : 'none'}
- Page text excerpt: \`${pageText.replace(/\s+/g, ' ').replace(/`/g, "'").slice(0, 1500)}\`

| Status | Check | Observed |
| --- | --- | --- |
${rows}
| FAIL | smoke failure | ${message.replace(/\n/g, '<br>')} |
`,
    );
    console.error(`Report: ${report}`);
    throw e;
  } finally {
    if (browser) await browser.close();
    vite.child.kill('SIGTERM');
    setTimeout(() => vite.child.kill('SIGKILL'), 2_000).unref();
  }
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
