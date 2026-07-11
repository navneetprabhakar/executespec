#!/usr/bin/env node
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require('/Users/navneetprabhakar/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const backend = process.env.BACKEND_URL || 'http://localhost:8080';
const frontend = process.env.FRONTEND_URL || 'http://localhost:5174';
const email = process.env.EXECUTESPEC_QA_EMAIL;
const password = process.env.EXECUTESPEC_QA_PASSWORD;
if (!email || !password) throw new Error('EXECUTESPEC_QA_EMAIL and EXECUTESPEC_QA_PASSWORD are required');
const outDir = path.resolve(process.env.WEB_RELEASE_CAPTURE_DIR || 'scripts/web-agent-release-results');
await mkdir(outDir, { recursive: true });

const login = await fetch(`${backend}/api/auth/login`, {
  method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({email, password}),
});
if (!login.ok) throw new Error(`login failed: ${login.status}`);
const {accessToken} = await login.json();
const fixture = await fetch(`${backend}/api/v2/test/web-fixture`, {method: 'POST', headers: {Authorization: `Bearer ${accessToken}`}});
if (!fixture.ok) throw new Error(`fixture failed: ${fixture.status} ${await fixture.text()}`);
const ids = await fixture.json();

const browser = await chromium.launch({headless: true, executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
const results = [];
try {
  for (const viewport of [{name: 'desktop', width: 1440, height: 900}, {name: 'mobile', width: 390, height: 844}]) {
    const page = await browser.newPage({viewport: {width: viewport.width, height: viewport.height}});
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror:${e.message}`));
    page.on('console', (m) => { if (['error', 'warning'].includes(m.type())) errors.push(`${m.type()}:${m.text()}`); });
    await page.addInitScript((token) => localStorage.setItem('access_token', token), accessToken);
    for (const [surface, url, marker] of [
      ['agent', '/agent', 'Fixture action is ready for approval'],
      ['run-success', `/result/${ids.successRunId}`, 'Release QA success'],
      ['run-failed', `/result/${ids.failedRunId}`, 'Release QA recovery'],
      ['run-gate', `/result/${ids.gateRunId}`, 'Deterministic release evidence fixture'],
    ]) {
      // The agent surface intentionally keeps live polling/stream connections open, so
      // `networkidle` never settles. DOM readiness plus the required semantic marker is the
      // meaningful capture boundary and avoids a false timeout on healthy live surfaces.
      await page.goto(`${frontend}${url}`, {waitUntil: 'domcontentloaded'});
      await page.waitForFunction(
        (expected) => document.body?.innerText?.includes(expected),
        marker,
        {timeout: 20_000},
      );
      const file = path.join(outDir, `${surface}-${viewport.name}.png`);
      await page.screenshot({path: file, fullPage: true});
      const body = await page.locator('body').innerText();
      results.push({surface, viewport: viewport.name, file, marker, errors: [...errors], bodyExcerpt: body.slice(0, 800)});
    }
    await page.close();
  }
} finally { await browser.close(); }
const failed = results.filter((r) => r.errors.length > 0);
const report = {schemaVersion: '2026-07-10', type: 'protected-web-release-capture', status: failed.length ? 'fail' : 'pass', backend, frontend, ids, results};
await writeFile(path.join(outDir, 'web-release-capture.json'), `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({status: report.status, outDir, ids, captures: results.length}, null, 2));
if (failed.length) process.exitCode = 1;
