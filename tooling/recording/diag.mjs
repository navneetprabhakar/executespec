import {chromium} from 'playwright';
const APP = 'http://localhost:5173';
const API = 'http://localhost:8080';
const EMAIL = process.env.ES_EMAIL;
const PASSWORD = process.env.ES_PASSWORD;
if (!EMAIL || !PASSWORD) throw new Error('Set ES_EMAIL and ES_PASSWORD');
const RUN_ID = 'cd2d6807-e696-4db9-bedd-9595f19b816d';
const PROJECT_ID = '6119f4b2-7622-48c3-a56f-a999b826d5c2';
const r = await fetch(`${API}/api/auth/login`, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:EMAIL,password:PASSWORD})});
const token = (await r.json()).accessToken;
const browser = await chromium.launch({headless:true});
const ctx = await browser.newContext({viewport:{width:1280,height:800},deviceScaleFactor:2});
const page = await ctx.newPage();
await page.addInitScript((t)=>localStorage.setItem('access_token',t), token);

await page.goto(`${APP}/home`,{waitUntil:'networkidle'});
await page.waitForTimeout(2500);
await page.screenshot({path:'/tmp/diag-home.png'});

await page.goto(`${APP}/result/${RUN_ID}`,{waitUntil:'networkidle'});
await page.waitForTimeout(2500);
await page.screenshot({path:'/tmp/diag-result-overview.png'});
// click Code tab
const codeBtn = page.locator('button.ide-rtab', {hasText:'Code'}).first();
await codeBtn.click();
await page.waitForTimeout(2500);
await page.screenshot({path:'/tmp/diag-result-code.png'});

await page.goto(`${APP}/projects/${PROJECT_ID}/architecture`,{waitUntil:'networkidle'});
await page.waitForTimeout(3500);
await page.screenshot({path:'/tmp/diag-arch.png'});
const mdLen = await page.locator('.signal-md').first().textContent().then(t=>t?.length).catch(()=>0);
const mdCount = await page.locator('.signal-md').count();
console.log('arch signal-md count=', mdCount, 'firstLen=', mdLen);

await page.goto(`${APP}/compose`,{waitUntil:'networkidle'});
await page.waitForTimeout(2000);
await page.screenshot({path:'/tmp/diag-compose.png'});
const taCount = await page.locator('textarea.ide-textarea').count();
console.log('compose textarea count=', taCount);

await browser.close();
console.log('done');
