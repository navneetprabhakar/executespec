// diag3.mjs — dry-run Phase B's exact locator logic against a terminal run.
// Throwaway. Confirms the verdict + code-diff selectors resolve + a .java file
// opens with a diff, BEFORE spending a real run + 12-min wait.
import {chromium} from 'playwright';
const APP = 'http://localhost:5173';
const API = 'http://localhost:8080';
const RUN_ID = process.env.RUN || 'cd2d6807-e696-4db9-bedd-9595f19b816d';
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
const log = (k, v) => console.log(`  ${k.padEnd(36)} ${v}`);

await page.goto(`${APP}/result/${RUN_ID}`, {waitUntil: 'networkidle'});
await page.waitForTimeout(2500);

// Verdict beat selectors
const scoreMatch = /\d+\s*\/\s*100/.test(await page.locator('body').innerText());
log('score /100 present', scoreMatch);
const firstCard = await page.locator('.ide-card').first().innerText().catch(() => '(none)');
log('first .ide-card starts', JSON.stringify(firstCard.slice(0, 40)));
log('first card has "Build"', firstCard.includes('Build'));
log('first card has "Tests"', firstCard.includes('Tests'));
log('first card has "Reviewer"', firstCard.includes('Reviewer'));

// Code tab
const codeTab = page.locator('button.ide-rtab', {hasText: 'Code'}).first();
await codeTab.click();
await page.waitForSelector('.ide-split-files .ide-tree-item', {timeout: 8000}).catch(() => {});
await page.waitForTimeout(1500);
const javaRows = page.locator('.ide-split-files .ide-tree-item', {hasText: '.java'});
log('java rows in tree', await javaRows.count());

// Click first .java row, confirm a file opens (tab + viewer)
const javaRow = javaRows.first();
const visible = await javaRow.isVisible().catch(() => false);
log('first java row visible (clickable)', visible);
if (visible) {
  await javaRow.click();
  await page.waitForTimeout(1500);
  const tabIcons = await page.locator('.ide-tabs .ide-tab .ide-ficon').count();
  log('open-tab Material icons', tabIcons);
  const viewer = await page.locator('.ide-split-main').innerText().catch(() => '');
  log('viewer non-empty', viewer.trim().length > 0);
  // Diff/Content toggle present?
  const hasDiffToggle = (await page.locator('text=Diff').count()) > 0;
  log('Content/Diff toggle present', hasDiffToggle);
}
await page.screenshot({path: '/tmp/diag3-code.png'});
await browser.close();
console.log('done — /tmp/diag3-code.png');
