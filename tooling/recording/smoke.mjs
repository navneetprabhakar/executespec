// Quick auth + route smoke probe (not the rig). Verifies the IDE loads
// authenticated via localStorage token injection before we invest in the
// full annotated recording. Run: node smoke.mjs
import {chromium} from 'playwright';

const APP = process.env.APP_URL || 'http://localhost:5173';
const API = process.env.API_URL || 'http://localhost:8080';
const EMAIL = process.env.ES_EMAIL;
const PASSWORD = process.env.ES_PASSWORD;
const RUN_ID = process.env.ES_RUN_ID;
const PROJECT_ID = process.env.ES_PROJECT_ID;

for (const [name, value] of Object.entries({ES_EMAIL: EMAIL, ES_PASSWORD: PASSWORD})) {
  if (!value) throw new Error(`${name} is required; pass disposable local QA credentials via the environment`);
}

async function mintToken() {
  const r = await fetch(`${API}/api/auth/login`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({email: EMAIL, password: PASSWORD}),
  });
  if (!r.ok) throw new Error(`login failed: ${r.status} ${await r.text()}`);
  const j = await r.json();
  if (!j.accessToken) throw new Error('no accessToken in login response');
  return j.accessToken;
}

const token = await mintToken();
console.log('token minted, len=', token.length);

const browser = await chromium.launch({headless: true});
const ctx = await browser.newContext({
  viewport: {width: 1280, height: 800},
  deviceScaleFactor: 2,
});
const page = await ctx.newPage();
page.on('console', (m) => {
  if (m.type() === 'error') console.log('  [browser-error]', m.text().slice(0, 160));
});

// Inject token before any app JS runs.
await page.addInitScript((t) => {
  try {
    localStorage.setItem('access_token', t);
  } catch (e) {}
}, token);

async function probe(path, waitSel) {
  await page.goto(`${APP}${path}`, {waitUntil: 'domcontentloaded'});
  try {
    if (waitSel) await page.waitForSelector(waitSel, {timeout: 8000});
  } catch (e) {
    console.log(`  WARN selector "${waitSel}" not found on ${path}`);
  }
  const url = page.url();
  const bodyLen = (await page.textContent('body'))?.length ?? 0;
  const hasLogin = url.includes('/login');
  console.log(`${path} -> ${url}  bodyLen=${bodyLen}  ${hasLogin ? 'REDIRECTED TO LOGIN ✗' : 'OK'}`);
  return !hasLogin;
}

let ok = true;
ok = (await probe('/home', '.ide-activitybar, .ide-root, [class*="ide"]')) && ok;
ok = (await probe('/compose', 'textarea.ide-textarea')) && ok;
if (RUN_ID) ok = (await probe(`/result/${RUN_ID}`, '.ide-rtab')) && ok;
if (PROJECT_ID) ok = (await probe(`/projects/${PROJECT_ID}/architecture`, '.ide-rtab')) && ok;

// Inspect run-result tabs + arch content quickly.
if (RUN_ID) {
  await page.goto(`${APP}/result/${RUN_ID}`, {waitUntil: 'domcontentloaded'});
  await page.waitForSelector('.ide-rtab', {timeout: 8000}).catch(() => {});
  const tabs = await page.$$eval('.ide-rtab', (els) => els.map((e) => e.textContent?.trim()));
  console.log('run-result tabs:', JSON.stringify(tabs));
}

await browser.close();
console.log(ok ? '\nSMOKE PASS — IDE loads authenticated.' : '\nSMOKE FAIL — see WARN/✗ above.');
process.exit(ok ? 0 : 1);
