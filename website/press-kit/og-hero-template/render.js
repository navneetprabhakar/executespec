// Renders template.html → og-hero.png at exactly 1200×630.
// Twitter/LinkedIn/FB caches expect 1200×630 exact — do NOT upscale.
const { chromium } = require('playwright-core');
const path = require('path');

(async () => {
  const dir = __dirname;
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH,
  });
  const ctx = await browser.newContext({
    viewport: { width: 1200, height: 630 },
    deviceScaleFactor: 1, // 1x — exact pixel output
  });
  const page = await ctx.newPage();
  await page.goto('file://' + path.join(dir, 'template.html'));
  // Wait for Google Fonts + screenshot to decode
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(() => {
    const img = new Image();
    img.src = 'screenshot.png';
    return img.complete || img.decode().then(() => true);
  });
  await page.waitForTimeout(500);
  await page.screenshot({
    path: path.join(dir, 'og-hero.png'),
    type: 'png',
    fullPage: false,
    clip: { x: 0, y: 0, width: 1200, height: 630 },
  });
  await browser.close();
  console.log('wrote', path.join(dir, 'og-hero.png'));
})();
