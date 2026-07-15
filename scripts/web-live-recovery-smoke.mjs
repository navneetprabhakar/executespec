#!/usr/bin/env node
import {createRequire} from 'node:module';
import {mkdir, writeFile} from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const {chromium} = require('/Users/navneetprabhakar/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(new URL('.', import.meta.url).pathname, '..');
const backend = process.env.BACKEND_URL || 'http://localhost:8092';
const frontend = process.env.FRONTEND_URL || 'http://localhost:5174';
const token = process.env.ACCESS_TOKEN;
const gateRunId = process.env.GATE_RUN_ID;
if (!token || !gateRunId) throw new Error('ACCESS_TOKEN and GATE_RUN_ID are required');
const outDir = path.join(root, 'scripts', 'web-live-recovery-results');
await mkdir(outDir, {recursive: true});

const browser = await chromium.launch({headless: true, executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
const page = await browser.newPage({viewport: {width: 390, height: 844}});
const events = [];
const sseRequests = [];
const statuses = [];
const errors = [];
let abortFirstSse = true;
await page.route('**/api/runs/*/events*', async (route) => {
  const requestUrl = new URL(route.request().url());
  requestUrl.searchParams.delete('token');
  sseRequests.push({at: new Date().toISOString(), url: requestUrl.toString(), aborted: abortFirstSse});
  if (abortFirstSse) { abortFirstSse = false; await route.abort('failed'); return; }
  await route.continue();
});
page.on('console', (m) => { if (['error', 'warning'].includes(m.type())) errors.push(`${m.type()}:${m.text()}`); });
page.on('pageerror', (e) => errors.push(`pageerror:${e.message}`));
page.on('response', async (r) => {
  if (r.url().includes(`/api/runs/${gateRunId}`) && r.status() >= 400) errors.push(`${r.status()} ${r.url()}`);
});
await page.goto(`${frontend}/favicon.svg`, {waitUntil: 'domcontentloaded'});
await page.evaluate((t) => localStorage.setItem('access_token', t), token);
await page.goto(`${frontend}/result/${gateRunId}`, {waitUntil: 'domcontentloaded'});
await page.waitForFunction(() => document.body?.innerText?.includes('Deterministic release evidence fixture'), null, {timeout: 20000});
const before = await page.locator('body').innerText();
statuses.push({label: 'initial gate', hasGate: /Review plan|Approve and start/i.test(before), hasReconnect: /Reconnecting/i.test(before)});

const auth = {Authorization: `Bearer ${token}`, 'Content-Type': 'application/json'};
const advance = async (status) => {
  const response = await fetch(`${backend}/api/v2/test/web-fixture/${gateRunId}/advance`, {method: 'POST', headers: auth, body: JSON.stringify({status})});
  if (!response.ok) throw new Error(`advance ${status} failed: ${response.status} ${await response.text()}`);
  return response.json();
};
await advance('EXECUTING');
// The UI's reconnect label is intentionally transient (the browser often retries within one
// EventSource turn), so retain the durable request-level proof as well: at least one aborted
// stream followed by a new stream carrying the replay cursor.
await page.waitForTimeout(1300);
const reconnectingText = await page.locator('body').innerText();
statuses.push({label: 'after forced SSE drop', hasReconnect: /Reconnecting/i.test(reconnectingText), streamRequests: sseRequests.length, replayCursorSeen: sseRequests.some((request) => request.url.includes('lastEventId='))});
await advance('COMPLETE');
await page.waitForFunction(() => document.body?.innerText?.includes('COMPLETE') || document.body?.innerText?.includes('Clean completion'), null, {timeout: 30000});
await page.screenshot({path: path.join(outDir, 'mobile-reconnect-complete.png'), fullPage: true});
const finalText = await page.locator('body').innerText();
statuses.push({label: 'reconnected terminal state', hasComplete: /COMPLETE|Clean completion/i.test(finalText), hasReconnectingCopy: /Reconnecting/i.test(finalText)});
const unexpectedErrors = errors.filter((error) => !error.includes('net::ERR_FAILED'));
const report = {schemaVersion: '2026-07-15', type: 'web-live-recovery-smoke', backend, frontend, gateRunId, viewport: {width: 390, height: 844}, sseRequests, statuses, errors, unexpectedErrors, screenshot: path.join(outDir, 'mobile-reconnect-complete.png'), result: sseRequests.length >= 2 && sseRequests.some((request) => request.aborted) && sseRequests.some((request) => request.url.includes('lastEventId=')) && statuses.at(-1)?.hasComplete && unexpectedErrors.length === 0 ? 'PASS' : 'FAIL'};
await writeFile(path.join(outDir, 'web-live-recovery.json'), `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
await browser.close();
if (report.result !== 'PASS') process.exitCode = 1;
