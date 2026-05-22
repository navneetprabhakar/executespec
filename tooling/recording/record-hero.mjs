#!/usr/bin/env node
/**
 * record-hero.mjs — Annotated screen-recording rig for the ExecuteSpec hero video.
 *
 * Produces a ~35-40s product walkthrough of the VS Code-style IDE chrome with a
 * synthetic animated cursor, click-pulse rings, and narration caption chips, then
 * normalises the raw Playwright .webm into a loop-friendly hero clip.
 *
 * Output: website/media/hero.webm  (autoplay loop muted on the marketing site).
 *
 * Usage (from anywhere; paths are resolved relative to this file):
 *   node tooling/recording/record-hero.mjs
 * Env overrides (all optional):
 *   APP_URL=http://localhost:5173  API_URL=http://localhost:8080
 *   ES_EMAIL=admin@executespec.ai  ES_PASSWORD=...   (login to mint the JWT)
 *   KEEP_RAW=1   keep the un-normalised raw .webm next to the output
 *   NO_FFMPEG=1  skip ffmpeg normalisation (ship Playwright's raw .webm as-is)
 *
 * Prereqs: `npm install` in this dir + `npx playwright install chromium`.
 * ffmpeg is optional but recommended (used to trim/normalise + extract frames).
 *
 * See README.md in this directory.
 */
import {chromium} from 'playwright';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {dirname, resolve} from 'node:path';
import {existsSync, mkdirSync, rmSync, renameSync, readdirSync, statSync, copyFileSync} from 'node:fs';

// ───────────────────────────── config ─────────────────────────────
const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..', '..');

const APP = process.env.APP_URL || 'http://localhost:5173';
const API = process.env.API_URL || 'http://localhost:8080';
const EMAIL = process.env.ES_EMAIL || 'admin@executespec.ai';
const PASSWORD = process.env.ES_PASSWORD || 'REDACTED_FOR_PUBLIC_RELEASE';

// Existing demo data — DO NOT submit new runs (LLM cost).
const RUN_ID = process.env.ES_RUN_ID || 'cd2d6807-e696-4db9-bedd-9595f19b816d';
const PROJECT_ID = process.env.ES_PROJECT_ID || '6119f4b2-7622-48c3-a56f-a999b826d5c2';

const W = 1280;
const H = 800;

const RAW_DIR = resolve(__dirname, '.video-out'); // Playwright writes raw .webm here
const OUT_DIR = resolve(REPO_ROOT, 'website', 'media');
const OUT_FILE = resolve(OUT_DIR, 'hero.webm');

const HAS_FFMPEG = !process.env.NO_FFMPEG && which('ffmpeg');

// ───────────────────────────── helpers ─────────────────────────────
function which(bin) {
  const r = spawnSync('which', [bin], {encoding: 'utf8'});
  return r.status === 0 ? r.stdout.trim() : null;
}

async function mintToken() {
  const r = await fetch(`${API}/api/auth/login`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({email: EMAIL, password: PASSWORD}),
  });
  if (!r.ok) throw new Error(`login failed: ${r.status} ${await r.text().catch(() => '')}`);
  const j = await r.json();
  if (!j.accessToken) throw new Error('login response had no accessToken');
  return j.accessToken;
}

// The annotation overlay. Injected fresh after every navigation (the DOM is wiped
// on goto, so we can't rely on addInitScript alone for the *elements* — but we DO
// use addInitScript so the helper functions exist before app JS runs). All drawing
// + tweening lives in-page on window.__rig so the Node side just awaits promises.
function overlaySource() {
  return `(() => {
    // document-start safety: <html> may not exist yet on the very first init-script
    // run. Bail quietly — the post-navigation page.evaluate() re-mount will take over.
    if (!document.documentElement) { window.__rig = window.__rig || {}; return; }
    if (window.__rig && window.__rig.ready && document.getElementById('rig-cursor')) return; // already mounted on this document
    const NS = window.__rig || (window.__rig = {});

    // Keep the page background dark even before the SPA paints, so a hard
    // navigation never flashes white between routes.
    try { document.documentElement.style.background = '#0d1117'; } catch (e) {}

    // ---- styles (idempotent) ----
    if (!document.getElementById('rig-style')) {
      const css = document.createElement('style');
      css.id = 'rig-style';
      css.textContent = \`
        html,body{background:#0d1117;}
        #rig-cursor{position:fixed;left:0;top:0;width:30px;height:30px;z-index:2147483646;
          pointer-events:none;transform:translate(-4px,-2px);filter:drop-shadow(0 2px 4px rgba(0,0,0,.55));
          transition:opacity .25s ease;will-change:left,top;}
        #rig-pulse{position:fixed;z-index:2147483645;pointer-events:none;border-radius:50%;
          border:2px solid #5B8DEF;left:0;top:0;width:14px;height:14px;margin:-7px 0 0 -7px;opacity:0;}
        @keyframes rig-ping{0%{transform:scale(.4);opacity:.9}80%{opacity:.35}100%{transform:scale(3.4);opacity:0}}
        .rig-ping{animation:rig-ping .65s cubic-bezier(.2,.7,.4,1) forwards;}
        #rig-cap{position:fixed;left:50%;bottom:46px;transform:translateX(-50%) translateY(8px);
          z-index:2147483646;pointer-events:none;max-width:78vw;
          background:rgba(13,17,23,.92);color:#E6EDF3;border:1px solid rgba(91,141,239,.55);
          border-radius:10px;padding:11px 20px;font:600 16px/1.35 'JetBrains Mono','SF Mono',ui-monospace,monospace;
          letter-spacing:.1px;box-shadow:0 8px 30px rgba(0,0,0,.45),0 0 0 1px rgba(0,0,0,.2);
          opacity:0;transition:opacity .4s ease,transform .4s ease;white-space:nowrap;
          overflow:hidden;text-overflow:ellipsis;}
        #rig-cap.show{opacity:1;transform:translateX(-50%) translateY(0);}
        #rig-cap .dot{display:inline-block;width:8px;height:8px;border-radius:50%;
          background:#3FB950;margin-right:10px;vertical-align:middle;box-shadow:0 0 8px #3FB950aa;}
        #rig-cover{position:fixed;inset:0;z-index:2147483647;background:#0d1117;
          pointer-events:none;opacity:0;transition:opacity .35s ease;}
        #rig-cover.show{opacity:1;}
      \`;
      document.documentElement.appendChild(css);
    }

    // ---- elements ----
    function mk(html){const d=document.createElement('div');d.innerHTML=html;return d.firstElementChild;}
    let cursor = document.getElementById('rig-cursor');
    if (!cursor) {
      cursor = mk(\`<svg id="rig-cursor" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M5 3l14 8.5-6.2 1.2 3.5 6.6-2.7 1.4-3.5-6.6L5 19V3z" fill="#fff" stroke="#0d1117" stroke-width="1.1" stroke-linejoin="round"/>
      </svg>\`);
      document.documentElement.appendChild(cursor);
    }
    let pulse = document.getElementById('rig-pulse');
    if (!pulse) { pulse = mk('<div id="rig-pulse"></div>'); document.documentElement.appendChild(pulse); }
    let cap = document.getElementById('rig-cap');
    if (!cap) { cap = mk('<div id="rig-cap"></div>'); document.documentElement.appendChild(cap); }
    let cover = document.getElementById('rig-cover');
    if (!cover) { cover = mk('<div id="rig-cover"></div>'); document.documentElement.appendChild(cover); }

    // start cursor near centre-left if unset
    if (NS.x == null) { NS.x = window.innerWidth*0.30; NS.y = window.innerHeight*0.42; }
    cursor.style.left = NS.x + 'px';
    cursor.style.top  = NS.y + 'px';

    // ---- API ----
    NS.setCaption = (text) => {
      if (!text) { cap.classList.remove('show'); return Promise.resolve(); }
      // fade out, swap, fade in
      cap.classList.remove('show');
      return new Promise((res) => {
        setTimeout(() => {
          cap.innerHTML = '<span class="dot"></span>' + text;
          cap.classList.add('show');
          setTimeout(res, 380);
        }, 160);
      });
    };

    NS.glideTo = (x, y, ms) => new Promise((res) => {
      const sx = NS.x, sy = NS.y, dx = x - sx, dy = y - sy;
      const dur = ms || 800, t0 = performance.now();
      const ease = (p) => (p < 0.5 ? 4*p*p*p : 1 - Math.pow(-2*p+2,3)/2); // easeInOutCubic
      function frame(now){
        const p = Math.min(1, (now - t0)/dur);
        const e = ease(p);
        NS.x = sx + dx*e; NS.y = sy + dy*e;
        cursor.style.left = NS.x + 'px';
        cursor.style.top  = NS.y + 'px';
        if (p < 1) requestAnimationFrame(frame); else res();
      }
      requestAnimationFrame(frame);
    });

    NS.pulseAt = (x, y) => {
      const px = x == null ? NS.x : x, py = y == null ? NS.y : y;
      pulse.style.left = px + 'px'; pulse.style.top = py + 'px';
      pulse.classList.remove('rig-ping'); void pulse.offsetWidth; pulse.classList.add('rig-ping');
      // tiny cursor "press" dip
      cursor.style.transform = 'translate(-4px,-2px) scale(.86)';
      setTimeout(() => { cursor.style.transform = 'translate(-4px,-2px) scale(1)'; }, 130);
    };

    NS.showCover = () => { const c = document.getElementById('rig-cover'); if (c) c.classList.add('show'); };
    NS.hideCover = () => { const c = document.getElementById('rig-cover'); if (c) c.classList.remove('show'); };

    NS.pos = () => ({x: NS.x, y: NS.y});
    NS.ready = true;
  })();`;
}

// ───────────────────────────── main ─────────────────────────────
async function main() {
  console.log(`[rig] app=${APP} api=${API}`);
  console.log(`[rig] ffmpeg: ${HAS_FFMPEG ? HAS_FFMPEG : 'NOT AVAILABLE (will ship raw .webm)'}`);

  // clean raw dir
  rmSync(RAW_DIR, {recursive: true, force: true});
  mkdirSync(RAW_DIR, {recursive: true});
  mkdirSync(OUT_DIR, {recursive: true});

  const token = await mintToken();
  console.log(`[rig] token minted (len=${token.length})`);

  const browser = await chromium.launch({headless: true});
  const ctx = await browser.newContext({
    viewport: {width: W, height: H},
    deviceScaleFactor: 2,
    recordVideo: {dir: RAW_DIR, size: {width: W, height: H}},
    colorScheme: 'dark',
  });
  // token before any app JS; overlay helpers before any app JS too
  await ctx.addInitScript((t) => {
    try {
      localStorage.setItem('access_token', t);
    } catch (e) {}
  }, token);
  await ctx.addInitScript(overlaySource());

  // Pre-warm every route on a THROWAWAY page (shares the context HTTP cache) so
  // the on-camera page's goto's resolve from cache and the recorded wall-clock
  // stays close to the scripted beat budget. Its own .webm is deleted below.
  console.log('[rig] pre-warming routes on a throwaway page…');
  const warm = await ctx.newPage();
  for (const p of [
    '/home',
    '/compose',
    `/result/${RUN_ID}`,
    `/projects/${PROJECT_ID}/pulse`,
    `/projects/${PROJECT_ID}/architecture`,
  ]) {
    await warm.goto(`${APP}${p}`, {waitUntil: 'domcontentloaded'}).catch(() => {});
    await warm.waitForTimeout(550);
  }
  const warmVideoPath = await warm.video()?.path().catch(() => null);
  await warm.close();
  if (warmVideoPath) rmSync(warmVideoPath, {force: true}); // discard pre-warm recording

  // The real recording page — its video is the one we ship.
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('  [pageerror]', String(e).slice(0, 140)));
  const T0 = Date.now();
  const mark = (label) => console.log(`  [t+${((Date.now() - T0) / 1000).toFixed(1)}s] ${label}`);

  // ---- low-level rig bindings ----
  const ensureOverlay = async () => {
    // re-mount overlay elements on the current document (survives SPA route swaps,
    // but a hard navigation wipes the DOM — addInitScript reruns the *functions*,
    // this guarantees the *elements* exist on whatever document is live now).
    await page.evaluate(overlaySource());
  };
  const caption = async (text) => {
    await ensureOverlay();
    await page.evaluate((t) => window.__rig.setCaption(t), text);
  };
  const glideToXY = async (x, y, ms = 800) => {
    await ensureOverlay();
    await page.evaluate(([x, y, ms]) => window.__rig.glideTo(x, y, ms), [x, y, ms]);
  };
  const pulseXY = async (x, y) => {
    await ensureOverlay();
    await page.evaluate(([x, y]) => window.__rig.pulseAt(x, y), [x, y]);
  };
  const hold = (ms) => page.waitForTimeout(ms);

  // center of a locator in viewport coords (null if not visible). Fail-fast: a
  // missing target must NOT block — boundingBox() auto-waits the full default
  // timeout otherwise (that 30s stall is exactly the bug this guards against).
  const centerOf = async (locator) => {
    try {
      await locator.waitFor({state: 'visible', timeout: 2500});
      const box = await locator.boundingBox();
      if (!box) return null;
      return {x: Math.round(box.x + box.width / 2), y: Math.round(box.y + box.height / 2)};
    } catch {
      return null;
    }
  };

  /**
   * step — the storyboard primitive.
   *  caption  : narration chip text (or null to clear)
   *  target   : Playwright Locator to glide to + pulse (optional)
   *  action   : async (target) => {} performed after the pulse (optional)
   *  glideMs  : cursor glide duration
   *  settle   : pause AFTER the action for readability (ms)
   *  preCaptionHold : pause after caption appears, before gliding (ms)
   */
  async function step({caption: cap, target, action, glideMs = 850, settle = 1500, preCaptionHold = 650}) {
    if (cap !== undefined) await caption(cap);
    if (cap) await hold(preCaptionHold);
    let pt = null;
    if (target) {
      pt = await centerOf(target);
      if (pt) {
        await glideToXY(pt.x, pt.y, glideMs);
        await hold(180);
        await pulseXY(pt.x, pt.y);
        await hold(220);
      }
    }
    if (action) await action(target, pt);
    if (settle) await hold(settle);
  }

  const gotoClean = async (path, waitSel, settle = 550) => {
    // Mask the route swap so the hard navigation never shows a white/half-painted
    // frame. The cover sits on the OLD doc while we navigate; the init-script keeps
    // the blank doc dark; we re-mount the overlay (with a fresh, already-shown
    // cover via showCover) over the loading new content, then fade it out.
    await page.evaluate(() => window.__rig && window.__rig.showCover && window.__rig.showCover());
    await page.waitForTimeout(360); // let the cover fade in on the old frame
    await page.goto(`${APP}${path}`, {waitUntil: 'domcontentloaded'});
    await ensureOverlay();
    await page.evaluate(() => window.__rig && window.__rig.showCover && window.__rig.showCover());
    if (waitSel) await page.waitForSelector(waitSel, {timeout: 12000}).catch(() => {});
    await page.waitForTimeout(settle); // let paint + data settle UNDER the cover
    await ensureOverlay();
    await page.evaluate(() => window.__rig && window.__rig.hideCover && window.__rig.hideCover());
    await page.waitForTimeout(420); // cover fade-out
  };

  // ════════════════════════ STORYBOARD ════════════════════════
  // Loop-friendly: opens AND closes on a clean /home frame. Target ≈40s.
  // Routes are already warm (throwaway page above), so the on-camera goto's are fast.

  // Paint the recording page dark immediately so the opening frame is never the
  // white about:blank default before the first navigation.
  await page.evaluate(() => {
    document.documentElement.style.background = '#0d1117';
    document.body && (document.body.style.background = '#0d1117');
  }).catch(() => {});

  // 1) Workspace — let the IDE shell + Explorer breathe. Short settle: the route
  // is pre-warmed so content paints fast, keeping the opening dark frame brief
  // (it's the loop seam — long black here would read as a stutter on `loop`).
  await gotoClean('/home', '.ide-side-body, .ide-activitybar', 250);
  await page.waitForTimeout(800); // intro beat on the clean Workspace (good loop seam)
  mark('step1 workspace');
  await step({
    caption: 'An agentic engineer that ships PRs.',
    // glide across the Explorer tree (real runs/projects) — a stable, always-present
    // focal point in the IDE shell.
    target: page.locator('.ide-side-body .ide-tree-item').first(),
    glideMs: 850,
    preCaptionHold: 500,
    action: async () => {},
    settle: 1600,
  });

  // 2) Compose — glide to spec textarea, TYPE a short spec (no submit).
  await gotoClean('/compose', 'textarea.ide-textarea');
  mark('step2 compose');
  const ta = page.locator('textarea.ide-textarea').first();
  await step({
    caption: 'Describe the change in plain English.',
    target: ta,
    glideMs: 800,
    preCaptionHold: 450,
    settle: 200,
    action: async (t) => {
      await t.click();
      await t.fill('');
      await t.type('Add a /health endpoint that returns build SHA + uptime, with a unit test.', {
        delay: 18,
      });
    },
  });
  await hold(1300); // let the typed spec sit on screen

  // 3) Run result — score + Signals, then click Code → diff.
  await gotoClean(`/result/${RUN_ID}`, '.ide-rtab');
  mark('step3 result');
  await step({
    caption: 'Every run ships a verified PR with a deterministic score.',
    target: page.locator('.ide-rtab', {hasText: 'Overview'}).first(),
    glideMs: 800,
    preCaptionHold: 450,
    action: async () => {},
    settle: 1300,
  });
  await step({
    caption: 'Browse the real diff.',
    target: page.locator('button.ide-rtab', {hasText: 'Code'}).first(),
    glideMs: 650,
    preCaptionHold: 400,
    settle: 500,
    action: async (t) => {
      await t.click();
      await page.waitForSelector('.ide-tree, .ide-pane', {timeout: 8000}).catch(() => {});
    },
  });
  await hold(1500); // dwell on the file tree + diff viewer
  mark('step3 done');

  // 4) Project architecture — navigate, click Architecture tab, scroll the doc.
  await gotoClean(`/projects/${PROJECT_ID}/pulse`, '.ide-rtab');
  mark('step4 project');
  await step({
    caption: 'Auto-generated architecture, per repo.',
    target: page.locator('a.ide-rtab', {hasText: 'Architecture'}).first(),
    glideMs: 750,
    preCaptionHold: 400,
    settle: 500,
    action: async (t) => {
      await t.click();
      // wait for the arch doc markdown to populate
      await page
        .waitForFunction(
          () => {
            const el = document.querySelector('.signal-md');
            return el && (el.textContent || '').length > 200;
          },
          {timeout: 12000},
        )
        .catch(() => {});
    },
  });
  await hold(1100); // read the arch doc top before scrolling
  // smooth-scroll the inner arch doc container (maxHeight:480 overflow:auto)
  await page.evaluate(() => {
    const el = document.querySelector('.signal-md');
    if (!el) return;
    const target = Math.min(el.scrollHeight - el.clientHeight, 340);
    const t0 = performance.now();
    const dur = 1900;
    function f(now) {
      const p = Math.min(1, (now - t0) / dur);
      const e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
      el.scrollTop = target * e;
      if (p < 1) requestAnimationFrame(f);
    }
    requestAnimationFrame(f);
  });
  await hold(2000);
  mark('step4 done');

  // 5) End on a clean workspace frame (good loop seam back to step 1).
  await gotoClean('/home', '.ide-side-body, .ide-activitybar');
  mark('step5 closing');
  await step({
    caption: 'ExecuteSpec — plan · code · test · PR.',
    target: null,
    settle: 2000,
  });
  await caption(null); // fade caption out for a clean closing frame
  await hold(700);
  mark('storyboard complete');

  // ════════════════════════ finalize ════════════════════════
  await page.close(); // flush
  await ctx.close(); // Playwright writes the .webm on context close
  await browser.close();

  // locate the raw webm
  const raws = readdirSync(RAW_DIR)
    .filter((f) => f.endsWith('.webm'))
    .map((f) => resolve(RAW_DIR, f))
    .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs);
  if (raws.length === 0) throw new Error('no raw .webm produced by Playwright');
  const raw = raws[0];
  const rawSize = (statSync(raw).size / 1e6).toFixed(2);
  console.log(`[rig] raw video: ${raw} (${rawSize} MB)`);

  if (!HAS_FFMPEG) {
    copyFileSync(raw, OUT_FILE);
    console.log(`[rig] (no ffmpeg) copied raw → ${OUT_FILE}`);
  } else {
    // Normalise: hard-cap duration (safety ceiling), even dims, decent VP9 quality,
    // muted (no audio anyway). -crf 37 / scale keeps it well under the 6 MB budget.
    const MAX_DURATION = Number(process.env.MAX_DURATION || 46); // seconds — hard ceiling
    const args = [
      '-y',
      '-i',
      raw,
      '-t',
      String(MAX_DURATION),
      '-an',
      '-c:v',
      'libvpx-vp9',
      '-b:v',
      '0',
      '-crf',
      '37',
      '-pix_fmt',
      'yuv420p',
      '-vf',
      'scale=1280:-2,fps=30',
      '-deadline',
      'good',
      '-cpu-used',
      '2',
      OUT_FILE,
    ];
    console.log(`[rig] ffmpeg normalise → ${OUT_FILE}`);
    const r = spawnSync(HAS_FFMPEG, args, {encoding: 'utf8'});
    if (r.status !== 0) {
      console.log('[rig] ffmpeg failed; falling back to raw copy. stderr tail:');
      console.log((r.stderr || '').split('\n').slice(-8).join('\n'));
      copyFileSync(raw, OUT_FILE);
    }
  }

  if (!process.env.KEEP_RAW) rmSync(RAW_DIR, {recursive: true, force: true});

  // report duration + size
  const finalSize = (statSync(OUT_FILE).size / 1e6).toFixed(2);
  let dur = '?';
  if (HAS_FFMPEG) {
    const probe = spawnSync(
      which('ffprobe') || 'ffprobe',
      ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', OUT_FILE],
      {encoding: 'utf8'},
    );
    if (probe.status === 0) dur = parseFloat(probe.stdout.trim()).toFixed(1);
  }
  console.log('\n──────────────────────────────────────────────');
  console.log(`[rig] DONE → ${OUT_FILE}`);
  console.log(`[rig] size=${finalSize} MB   duration=${dur}s`);
  console.log('──────────────────────────────────────────────');
}

main().catch((e) => {
  console.error('[rig] FAILED:', e);
  process.exit(1);
});
