// diag2.mjs — probe the EXACT surfaces the new single-thread storyboard drives.
// Throwaway diagnostic (not the rig). Confirms selectors before the full record.
import {chromium} from 'playwright';
const APP = 'http://localhost:5173';
const API = 'http://localhost:8080';
const PROJECT_ID = '6119f4b2-7622-48c3-a56f-a999b826d5c2';
const REF_RUN = 'cd2d6807-e696-4db9-bedd-9595f19b816d'; // Java project run — Code-tab tree proof
const r = await fetch(`${API}/api/auth/login`, {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({email: 'admin@executespec.ai', password: 'g7kZfo#oKYrI'}),
});
const token = (await r.json()).accessToken;
const browser = await chromium.launch({headless: true});
const ctx = await browser.newContext({
  viewport: {width: 1280, height: 800},
  deviceScaleFactor: 2,
  colorScheme: 'dark',
});
const page = await ctx.newPage();
await page.addInitScript((t) => localStorage.setItem('access_token', t), token);
const log = (k, v) => console.log(`  ${k.padEnd(34)} ${v}`);

// Beat 1 — Workspace home
await page.goto(`${APP}/home`, {waitUntil: 'networkidle'});
await page.waitForTimeout(2000);
log('home .ide-side-body', await page.locator('.ide-side-body').count());
log('home .ide-tree-item', await page.locator('.ide-side-body .ide-tree-item').count());
await page.screenshot({path: '/tmp/diag2-home.png'});

// Beat 2 — Project Architecture (lands on Current sub-tab w/ signal-md doc)
await page.goto(`${APP}/projects/${PROJECT_ID}/architecture`, {waitUntil: 'networkidle'});
await page.waitForTimeout(3500);
const mdCount = await page.locator('.signal-md').count();
const mdLen = await page
  .locator('.signal-md')
  .first()
  .textContent()
  .then((t) => t?.length || 0)
  .catch(() => 0);
log('arch .signal-md count', mdCount);
log('arch .signal-md firstLen', mdLen);
log('arch ProjectTabs a.ide-rtab', await page.locator('a.ide-rtab').count());
await page.screenshot({path: '/tmp/diag2-arch.png'});

// Beat 3 — Project Compose surface (/projects/:id → IdeProject)
await page.goto(`${APP}/projects/${PROJECT_ID}`, {waitUntil: 'networkidle'});
await page.waitForTimeout(2500);
log('compose textarea.ide-textarea', await page.locator('textarea.ide-textarea').count());
const submitTxt = await page
  .locator('button.ide-btn', {hasText: 'Submit run'})
  .first()
  .textContent()
  .catch(() => '(none)');
log('compose Submit btn text', JSON.stringify(submitTxt));
log('compose repo badges .ide-badge', await page.locator('.ide-badge').count());
await page.screenshot({path: '/tmp/diag2-project-compose.png'});

// Beat 6 — Verdict (Overview + score + Signals) on a Java project run
await page.goto(`${APP}/result/${REF_RUN}`, {waitUntil: 'networkidle'});
await page.waitForTimeout(2500);
log('result .ide-rtabs', await page.locator('.ide-rtabs').count());
log('result Code tab button', await page.locator('button.ide-rtab', {hasText: 'Code'}).count());
const scoreTxt = await page
  .locator('.ide-pane')
  .first()
  .textContent()
  .then((t) => (t || '').match(/\d+\s*\/\s*100/)?.[0] || '(no score)')
  .catch(() => '(err)');
log('result score on overview', scoreTxt);
await page.screenshot({path: '/tmp/diag2-result-overview.png'});

// Beat 6b — Code tab → file tree w/ Material icons (.ide-tree-item + .ide-ficon)
await page.locator('button.ide-rtab', {hasText: 'Code'}).first().click();
await page.waitForTimeout(3000);
log('code .ide-split-files', await page.locator('.ide-split-files').count());
log('code tree items .ide-tree-item', await page.locator('.ide-split-files .ide-tree-item').count());
log('code Material icons .ide-ficon', await page.locator('.ide-split-files .ide-ficon').count());
const javaFiles = await page.locator('.ide-split-files .ide-tree-item', {hasText: '.java'}).count();
log('code .java rows visible', javaFiles);
await page.screenshot({path: '/tmp/diag2-result-code.png'});

await browser.close();
console.log('done — screenshots in /tmp/diag2-*.png');
