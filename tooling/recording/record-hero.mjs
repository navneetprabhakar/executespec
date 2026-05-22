#!/usr/bin/env node
/**
 * record-hero.mjs — Annotated screen-recording rig for the ExecuteSpec hero video.
 *
 * Records ONE coherent product story end-to-end: a single fresh run against a
 * single real project (the tictactoe project), threaded through every beat —
 * workspace → real architecture → describe the change → plan gate → approve →
 * build/test → a trustworthy verdict (Signals + score + the real diff with the
 * actual VS Code Material file icons) → back to the workspace.
 *
 * Because a real run takes minutes, the rig records in TWO phases around the live
 * execution and stitches them into one clip:
 *   • Phase A  — beats 1→5: workspace, architecture, compose+type, SUBMIT, wait for
 *                the plan gate, show the planned tasks, APPROVE, a short glimpse of
 *                the executing state.  (This submits + approves a REAL run, which
 *                clones the repo, builds, tests, and opens a real PR — intended.)
 *   • WAIT     — polls GET /api/runs/{runId} (not recording) until the run is
 *                terminal (COMPLETE / COMPLETED / PARTIAL).
 *   • Phase B  — beats 6→7: the same run's verdict (Signals + score), the Code tab
 *                diff (real Material icons in the file tree), then back to /home.
 *   • STITCH   — ffmpeg concat A+B (crossfade at the seam) → one VP9 clip.
 *
 * Output: website/media/hero.webm  (autoplay loop muted on the marketing site).
 *
 * Usage (from anywhere; paths are resolved relative to this file):
 *   node tooling/recording/record-hero.mjs
 * Env overrides (all optional):
 *   APP_URL=http://localhost:5173  API_URL=http://localhost:8080
 *   ES_EMAIL=admin@executespec.ai  ES_PASSWORD=...   (login to mint the JWT)
 *   ES_PROJECT_ID=6119f4b2-…       the single project the whole story runs against
 *   ES_RUN_SPEC="Add a hasWinner() …"  the spec typed + submitted in beat 3
 *   ES_RUN_ID=<uuid>               SKIP submitting — reuse an existing terminal run
 *                                  for Phase B (re-renders the verdict beats only;
 *                                  Phase A still records the live submit→approve
 *                                  unless ES_SKIP_PHASE_A=1).
 *   RUN_TIMEOUT_MS=720000          how long WAIT polls for the run to finish (12m).
 *   KEEP_RAW=1   keep the un-normalised raw phase clips next to the output.
 *   NO_FFMPEG=1  skip ffmpeg (ship Phase A raw only; cannot stitch — for debugging).
 *
 * Prereqs: `npm install` in this dir + `npx playwright install chromium`.
 * ffmpeg is REQUIRED for the two-phase stitch (without it only Phase A ships).
 *
 * See README.md in this directory.
 */
import {chromium} from 'playwright';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {dirname, resolve} from 'node:path';
import {existsSync, mkdirSync, rmSync, readdirSync, statSync, copyFileSync} from 'node:fs';

// ───────────────────────────── config ─────────────────────────────
const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..', '..');

const APP = process.env.APP_URL || 'http://localhost:5173';
const API = process.env.API_URL || 'http://localhost:8080';
const EMAIL = process.env.ES_EMAIL || 'admin@executespec.ai';
const PASSWORD = process.env.ES_PASSWORD || 'g7kZfo#oKYrI';

// The single use case — the tictactoe project. The whole story is THIS project +
// ONE fresh run against it.
const PROJECT_ID = process.env.ES_PROJECT_ID || '6119f4b2-7622-48c3-a56f-a999b826d5c2';
// The spec typed + submitted in beat 3. Small, real, and testable against the repo.
const RUN_SPEC =
  process.env.ES_RUN_SPEC ||
  'Add a hasWinner() helper to TicTacToe that returns the winning mark or null, with a unit test.';
// Optional: reuse an existing terminal run instead of submitting a fresh one.
const REUSE_RUN_ID = process.env.ES_RUN_ID || '';
const SKIP_PHASE_A = process.env.ES_SKIP_PHASE_A === '1';
const RUN_TIMEOUT_MS = Number(process.env.RUN_TIMEOUT_MS || 720000); // 12 min
// Final-clip duration band. The stitched clip is fit INTO [TARGET_MIN_S, TARGET_MAX_S]
// with a mild, imperceptible uniform setpts ramp — sped up if over the ceiling,
// slowed (stretched) if under the floor — capped by MAX_SPEEDUP / MAX_SLOWDOWN. This
// holds the hero clip in the marketing-site budget despite run-to-run planning
// variance, in BOTH directions.
const TARGET_MAX_S = Number(process.env.TARGET_MAX_S || 64);
const TARGET_MIN_S = Number(process.env.TARGET_MIN_S || 56);
const MAX_SPEEDUP = Number(process.env.MAX_SPEEDUP || 1.35);
const MAX_SLOWDOWN = Number(process.env.MAX_SLOWDOWN || 1.3); // ≤1.3× stretch reads as relaxed pacing

const W = 1280;
const H = 800;

const RAW_DIR = resolve(__dirname, '.video-out'); // Playwright writes raw phase .webms here
const OUT_DIR = resolve(REPO_ROOT, 'website', 'media');
const OUT_FILE = resolve(OUT_DIR, 'hero.webm');
const RAW_A = resolve(RAW_DIR, 'phaseA.webm');
const RAW_B = resolve(RAW_DIR, 'phaseB.webm');

const HAS_FFMPEG = !process.env.NO_FFMPEG && which('ffmpeg');

const TERMINAL = ['COMPLETE', 'COMPLETED', 'PARTIAL'];
const TERMINAL_BAD = ['FAILED', 'ERROR', 'CANCELLED'];

// ───────────────────────────── helpers ─────────────────────────────
function which(bin) {
  const r = spawnSync('which', [bin], {encoding: 'utf8'});
  return r.status === 0 ? r.stdout.trim() : null;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

async function fetchRun(token, runId) {
  const r = await fetch(`${API}/api/runs/${runId}`, {
    headers: {Authorization: `Bearer ${token}`},
  });
  if (!r.ok) throw new Error(`getRun ${runId}: ${r.status}`);
  return r.json();
}

/**
 * The annotation overlay. Injected fresh after every navigation (the DOM is wiped
 * on goto, so we can't rely on addInitScript alone for the *elements* — but we DO
 * use addInitScript so the helper functions exist before app JS runs). All drawing
 * + tweening lives in-page on window.__rig so the Node side just awaits promises.
 */
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

/**
 * Bind the storyboard primitives to a Playwright `page`. Returns the helper set
 * (caption / glide / pulse / step / gotoClean / hold) so each phase reuses the
 * same overlay-aware machinery.
 */
function makeRig(page) {
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
  const centerOf = async (locator, timeout = 3000) => {
    try {
      await locator.waitFor({state: 'visible', timeout});
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

  return {ensureOverlay, caption, glideToXY, pulseXY, hold, centerOf, step, gotoClean};
}

/** New authenticated, dark, video-recording context + page bound to the rig. */
async function openPhase(browser, token, rawTargetName) {
  // recStart ≈ the video timeline's t=0 (Playwright starts recording on context
  // create). recClock() returns seconds into the recorded clip — used to mark spans
  // (e.g. the planning dead-air) the stitch should cut.
  const recStart = Date.now();
  const ctx = await browser.newContext({
    viewport: {width: W, height: H},
    deviceScaleFactor: 2,
    recordVideo: {dir: RAW_DIR, size: {width: W, height: H}},
    colorScheme: 'dark',
  });
  await ctx.addInitScript((t) => {
    try {
      localStorage.setItem('access_token', t);
    } catch (e) {}
  }, token);
  await ctx.addInitScript(overlaySource());
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('  [pageerror]', String(e).slice(0, 140)));
  const recClock = () => (Date.now() - recStart) / 1000;
  const mark = (label) => console.log(`  [t+${recClock().toFixed(1)}s] ${label}`);
  const rig = makeRig(page);
  // Paint the recording page dark immediately so the opening frame is never the
  // white about:blank default before the first navigation.
  await page
    .evaluate(() => {
      document.documentElement.style.background = '#0d1117';
      document.body && (document.body.style.background = '#0d1117');
    })
    .catch(() => {});
  return {ctx, page, rig, mark, recClock};
}

/** Close a phase context and rename its raw .webm to a stable per-phase path. */
async function closePhase(ctx, page, target) {
  const videoPath = await page.video()?.path().catch(() => null);
  await page.close(); // flush
  await ctx.close(); // Playwright writes the .webm on context close
  if (videoPath && existsSync(videoPath)) {
    rmSync(target, {force: true});
    copyFileSync(videoPath, target);
    rmSync(videoPath, {force: true});
  }
  const size = existsSync(target) ? (statSync(target).size / 1e6).toFixed(2) : '0';
  console.log(`[rig] phase clip → ${target} (${size} MB)`);
}

// ──────────────────────── PHASE A: intro → submit → gate → approve ────────────────────────
// Records beats 1→5. Submits a REAL run + approves it. Returns {runId, deadAir}.
async function recordPhaseA(browser, token) {
  console.log('\n════════ PHASE A — workspace → architecture → describe → gate → approve ════════');
  const {ctx, page, rig, mark, recClock} = await openPhase(browser, token, RAW_A);
  const {step, gotoClean, caption, hold, glideToXY, pulseXY, centerOf, ensureOverlay} = rig;
  const deadAir = []; // [startSec, endSec] spans to cut from this phase's clip

  // 1) Workspace — let the IDE shell + Explorer breathe (loop seam).
  await gotoClean('/home', '.ide-side-body, .ide-activitybar', 250);
  await hold(500);
  mark('beat1 workspace');
  await step({
    caption: 'Ship PRs your team will actually merge.',
    target: page.locator('.ide-side-body .ide-tree-item').first(),
    glideMs: 750,
    preCaptionHold: 450,
    action: async () => {},
    settle: 1000,
  });

  // 2) Your real codebase — Architecture doc for the tictactoe project, scrolled.
  await gotoClean(`/projects/${PROJECT_ID}/architecture`, '.signal-md', 600);
  // wait for the arch doc markdown to actually populate
  await page
    .waitForFunction(
      () => {
        const el = document.querySelector('.signal-md');
        return el && (el.textContent || '').length > 300;
      },
      {timeout: 12000},
    )
    .catch(() => {});
  mark('beat2 architecture');
  await step({
    caption: 'Point it at your real repo — it maps the architecture first.',
    target: page.locator('a.ide-rtab', {hasText: 'Architecture'}).first(),
    glideMs: 650,
    preCaptionHold: 450,
    action: async () => {},
    settle: 450,
  });
  // smooth-scroll the inner arch doc container (maxHeight:480 overflow:auto)
  await page.evaluate(() => {
    const el = document.querySelector('.signal-md');
    if (!el) return;
    const target = Math.min(el.scrollHeight - el.clientHeight, 340);
    const t0 = performance.now();
    const dur = 1600;
    function f(now) {
      const p = Math.min(1, (now - t0) / dur);
      const e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
      el.scrollTop = target * e;
      if (p < 1) requestAnimationFrame(f);
    }
    requestAnimationFrame(f);
  });
  await hold(1500);
  mark('beat2 done');

  // 3) Describe — the project Compose surface; type the spec into the box.
  await gotoClean(`/projects/${PROJECT_ID}`, 'textarea.ide-textarea', 500);
  mark('beat3 compose');
  const ta = page.locator('textarea.ide-textarea').first();
  await step({
    caption: 'Describe the change in plain English.',
    target: ta,
    glideMs: 700,
    preCaptionHold: 400,
    settle: 150,
    action: async (t) => {
      await t.click();
      await t.fill('');
      await t.type(RUN_SPEC, {delay: 13});
    },
  });
  await hold(700); // let the typed spec sit on screen

  // 3b) Scope the run to the real repo — click the repo badge. THIS is what makes
  // the pipeline clone the linked repo into workspace/<runId>/repos/ and edit the
  // existing code (instead of generating a fresh project from scratch). Without it
  // selectedRepoIds is empty → no clone → no PR → an off-stack diff. Critical.
  const repoBadge = page.locator('button.ide-badge').first();
  await step({
    caption: 'Scope it to your repo — it edits the code that’s already there.',
    target: repoBadge,
    glideMs: 600,
    preCaptionHold: 400,
    settle: 200,
    action: async (b) => {
      await b.click();
    },
  });
  // Verify the badge actually toggled ON (✓ prefix) — fail fast if not, so we never
  // ship a from-scratch run that ignored the repo.
  const badgeOn = await repoBadge
    .textContent()
    .then((t) => (t || '').includes('✓'))
    .catch(() => false);
  if (!badgeOn) {
    throw new Error('PHASE A: repo badge did not toggle ON — run would not clone the repo. Aborting.');
  }
  await hold(450);

  // 4) Submit — click Submit run; the surface routes to /live/{runId}; capture id.
  let runId = '';
  await caption('You approve the plan before a single token is spent.');
  await hold(500);
  const submitBtn = page.locator('button.ide-btn', {hasText: 'Submit run'}).first();
  const sp = await centerOf(submitBtn);
  if (sp) {
    await glideToXY(sp.x, sp.y, 700);
    await hold(160);
    await pulseXY(sp.x, sp.y);
  }
  // Click + wait for the navigation to /live/{id} (IdeProject.submit navigates there).
  await Promise.all([
    page.waitForURL(/\/live\/[0-9a-f-]+/i, {timeout: 20000}).catch(() => {}),
    submitBtn.click(),
  ]);
  const m = page.url().match(/\/live\/([0-9a-f-]+)/i);
  runId = m ? m[1] : '';
  if (!runId) throw new Error(`PHASE A: submit did not navigate to /live/{id} (url=${page.url()})`);
  console.log(`[rig] submitted run → ${runId}`);
  mark('beat4 submitted');

  // DEAD AIR: from here until the plan gate is fully rendered, nothing watchable
  // happens (the planner runs, then the /plan page loads). We raise a dark cover +
  // let it FULLY fade in, mark the cut-span start, keep the cover up across BOTH the
  // planning poll AND the /plan navigation+render, then mark the end right before we
  // reveal. The stitch cuts that whole span (the founder's "time-lapse in post"); both
  // cut boundaries land on identical black frames so the splice is invisible.
  await ensureOverlay();
  await page.evaluate(() => window.__rig && window.__rig.showCover && window.__rig.showCover());
  await hold(450); // full cover fade-in (the css transition is .35s)
  const deadStart = recClock() + 0.2; // inset into the solid-dark region
  console.log(`[rig] DEADAIR_START ${deadStart.toFixed(2)}`);
  let status = '';
  for (let i = 0; i < 90; i++) {
    // up to ~3 min for planning
    try {
      const r = await fetchRun(token, runId);
      status = (r.status || '').toUpperCase();
    } catch {}
    if (status === 'AWAITING_APPROVAL') break;
    if (TERMINAL.includes(status) || TERMINAL_BAD.includes(status)) break;
    await sleep(2000);
  }
  if (status !== 'AWAITING_APPROVAL') {
    throw new Error(
      `PHASE A: run ${runId} never reached the plan gate (last status=${status}). Aborting — won't ship a video missing the gate beat.`,
    );
  }
  console.log(`[rig] run ${runId} reached the plan gate`);

  // Navigate to /plan UNDER the cover (no reveal yet) + wait for the task list to
  // actually render, so the slow gate-page load is part of the cut, not the show.
  await page.goto(`${APP}/plan/${runId}`, {waitUntil: 'domcontentloaded'});
  await ensureOverlay();
  await page.evaluate(() => window.__rig && window.__rig.showCover && window.__rig.showCover());
  await page
    .waitForFunction(() => document.body.innerText.includes('Tasks ·'), {timeout: 20000})
    .catch(() => {});
  await hold(300); // let the gate paint settle under the cover
  const deadEnd = recClock() - 0.1; // still inside the solid-dark region
  console.log(`[rig] DEADAIR_END ${deadEnd.toFixed(2)}`);
  if (deadEnd - deadStart > 1.0) deadAir.push([deadStart, deadEnd]);
  // Reveal the fully-rendered gate.
  await ensureOverlay();
  await page.evaluate(() => window.__rig && window.__rig.hideCover && window.__rig.hideCover());
  await hold(420); // cover fade-out
  mark('beat4 gate');
  await step({
    caption: 'Review the planned tasks — then approve.',
    target: page.locator('.ide-card').nth(1),
    glideMs: 700,
    preCaptionHold: 450,
    action: async () => {},
    settle: 1200,
  });

  // Click Approve (charges credits, kicks off real execution).
  const approveBtn = page.locator('button.ide-btn', {hasText: 'Approve'}).first();
  const ap = await centerOf(approveBtn, 8000);
  if (!ap) throw new Error('PHASE A: Approve button not found on the plan gate.');
  await glideToXY(ap.x, ap.y, 700);
  await hold(160);
  await pulseXY(ap.x, ap.y);
  await hold(160);
  await Promise.all([
    page.waitForURL(/\/live\/[0-9a-f-]+/i, {timeout: 15000}).catch(() => {}),
    approveBtn.click(),
  ]);
  mark('beat4 approved');

  // 5) Build + test — a short glimpse of the executing/live state (no idle minutes).
  await gotoClean(`/live/${runId}`, '.ide-pane', 400);
  await caption('It codes across your repo, builds, and runs your tests.');
  await hold(2200); // ~2.2s glimpse of the executing state, then stop recording
  mark('beat5 executing glimpse');

  await caption(null);
  await hold(300);
  await closePhase(ctx, page, RAW_A);
  return {runId, deadAir};
}

// ──────────────────────── WAIT: poll until the run is terminal ────────────────────────
async function waitForRun(token, runId) {
  console.log(`\n════════ WAIT — polling run ${runId} until terminal (timeout ${Math.round(RUN_TIMEOUT_MS / 1000)}s) ════════`);
  const t0 = Date.now();
  let last = '';
  while (Date.now() - t0 < RUN_TIMEOUT_MS) {
    let r;
    try {
      r = await fetchRun(token, runId);
    } catch (e) {
      await sleep(5000);
      continue;
    }
    const s = (r.status || '').toUpperCase();
    if (s !== last) {
      console.log(`  [t+${((Date.now() - t0) / 1000).toFixed(0)}s] status=${s}`);
      last = s;
    }
    if (TERMINAL.includes(s)) return {status: s, run: r};
    if (TERMINAL_BAD.includes(s))
      throw new Error(`run ${runId} ended ${s} — won't ship a video with a failed verdict beat.`);
    await sleep(6000);
  }
  throw new Error(`run ${runId} did not finish within ${Math.round(RUN_TIMEOUT_MS / 1000)}s (last status=${last}).`);
}

// ──────────────────────── PHASE B: verdict → code diff → close ────────────────────────
// Records beats 6→7 for the SAME runId.
async function recordPhaseB(browser, token, runId) {
  console.log(`\n════════ PHASE B — verdict → code diff → close (run ${runId}) ════════`);
  const {ctx, page, rig, mark} = await openPhase(browser, token, RAW_B);
  const {step, gotoClean, caption, hold, glideToXY, pulseXY, centerOf} = rig;

  // 6) Verdict — Overview: Signals (Build/Tests/Reviewer) + the score.
  await gotoClean(`/result/${runId}`, '.ide-rtabs', 700);
  // wait for the score / signals to populate
  await page
    .waitForFunction(() => /\d+\s*\/\s*100/.test(document.body.innerText), {timeout: 12000})
    .catch(() => {});
  mark('beat6 verdict');
  await step({
    caption: 'Then a verdict you can trust — real signals, not an LLM’s opinion.',
    // glide to the Signals card (first .ide-card on Overview shows Build/Tests/Reviewer)
    target: page.locator('.ide-card').first(),
    glideMs: 750,
    preCaptionHold: 500,
    action: async () => {},
    settle: 1700,
  });

  // 6b) Code tab → file tree (real Material icons) + diff. Open a generated source
  // file so the diff + the language icon are both on screen.
  const codeTab = page.locator('button.ide-rtab', {hasText: 'Code'}).first();
  await step({
    caption: 'Browse the real diff — every file, your stack’s icons.',
    target: codeTab,
    glideMs: 650,
    preCaptionHold: 450,
    settle: 600,
    action: async (t) => {
      await t.click();
      await page.waitForSelector('.ide-split-files .ide-tree-item', {timeout: 8000}).catch(() => {});
    },
  });
  // Open a CHANGED source file so the green/red diff is on screen — not an unchanged
  // file (which renders as plain content). Prefer, in order: the headline edited file
  // (TicTacToe* — the spec's target, a real MODIFIED diff), then any *Test* file (the
  // ADDED unit test, all-green), then the first row whose diff status is MODIFIED or
  // ADDED, then any source row. The Material language glyph shows in every case.
  const candidates = [
    page.locator('.ide-split-files .ide-tree-item', {hasText: /TicTacToe(?!Test).*\.java$/}),
    page.locator('.ide-split-files .ide-tree-item', {hasText: /Test.*\.(java|py|ts|js)$/}),
    page.locator('.ide-split-files .ide-tree-item', {hasText: /\.(java|ts|js|py)$/}),
    page
      .locator('.ide-split-files .ide-tree-item')
      .filter({hasText: /\.(java|ts|js|py|md|json|xml|yml|yaml)$/}),
  ];
  let fileTarget = null;
  for (const c of candidates) {
    if (await c.count()) {
      fileTarget = c.first();
      break;
    }
  }
  const fp = fileTarget ? await centerOf(fileTarget, 4000) : null;
  if (fp) {
    await glideToXY(fp.x, fp.y, 600);
    await hold(150);
    await pulseXY(fp.x, fp.y);
    await fileTarget.click().catch(() => {});
    await hold(700);
    // The viewer defaults to the Diff tab for ADDED/MODIFIED files; if a Diff toggle
    // is present, click it to guarantee the green additions are showing.
    const diffToggle = page.locator('.ide-split-main').getByText('Diff', {exact: true}).first();
    if (await diffToggle.count()) await diffToggle.click().catch(() => {});
  }
  await hold(1900); // dwell on the file tree (Material icons) + diff viewer
  mark('beat6 code diff');

  // 7) Close — back to the workspace (clean loop seam back to beat 1).
  await gotoClean('/home', '.ide-side-body, .ide-activitybar', 300);
  mark('beat7 closing');
  await step({
    caption: 'Plan · Build · Verify · Merge.',
    target: null,
    preCaptionHold: 450,
    settle: 1500,
  });
  await caption(null); // fade caption out for a clean closing frame
  await hold(600);
  mark('phase B complete');

  await closePhase(ctx, page, RAW_B);
}

// ──────────────────────── STITCH: A + B → one VP9 clip ────────────────────────
function ffprobeDuration(file) {
  const probe = spawnSync(
    which('ffprobe') || 'ffprobe',
    ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file],
    {encoding: 'utf8'},
  );
  return probe.status === 0 ? parseFloat(probe.stdout.trim()) : NaN;
}

/**
 * Build the ffmpeg filter chain for Phase A with the dead-air spans CUT out.
 * Keeps the complement of `deadAir` (e.g. [0,start] ++ [end,dur]) by trimming each
 * kept segment and concatenating them. Returns {filter, outLabel} where outLabel is
 * the stream label carrying the cut-down, scaled, 30fps Phase A.
 */
// CFR + uniform-format suffix. xfade rejects the concat output's timebase unless
// it's re-stamped to a constant frame rate + a normalised timebase (settb=AVTB) +
// fixed pixel format — without this the filtergraph errors with "reinitializing
// filters" / "-22 Invalid argument". Applied to BOTH xfade inputs.
const NORM = `fps=30,settb=AVTB,setpts=PTS-STARTPTS,format=yuv420p`;
const SCALE = `scale=1280:-2,setsar=1`;

function phaseAFilter(dur, deadAir) {
  // Normalise + sort + clamp the dead-air spans.
  const spans = (deadAir || [])
    .map(([s, e]) => [Math.max(0, s), Math.min(dur, e)])
    .filter(([s, e]) => e - s > 0.5)
    .sort((a, b) => a[0] - b[0]);
  if (spans.length === 0) {
    return {filter: `[0:v]${SCALE},${NORM}[a]`, outLabel: 'a'};
  }
  // Build the kept (complement) segments.
  const keep = [];
  let cursor = 0;
  for (const [s, e] of spans) {
    if (s - cursor > 0.3) keep.push([cursor, s]);
    cursor = e;
  }
  if (dur - cursor > 0.3) keep.push([cursor, dur]);
  // Each kept segment → its own trimmed+scaled stream, then concat, then re-stamp
  // CFR + timebase so xfade downstream accepts it.
  const parts = [];
  const labels = [];
  keep.forEach(([s, e], i) => {
    const lbl = `ak${i}`;
    parts.push(
      `[0:v]trim=start=${s.toFixed(3)}:end=${e.toFixed(3)},setpts=PTS-STARTPTS,${SCALE},fps=30[${lbl}]`,
    );
    labels.push(`[${lbl}]`);
  });
  parts.push(`${labels.join('')}concat=n=${labels.length}:v=1:a=0,${NORM}[a]`);
  return {filter: parts.join(';'), outLabel: 'a'};
}

function stitch(deadAirIn = []) {
  // Cut the planning dead-air out of Phase A, then crossfade A→B (0.4s xfade),
  // normalise to VP9 1280×(even) @30fps, muted. -crf 36 keeps the clip < 6 MB.
  const XF = 0.4;
  const dA = ffprobeDuration(RAW_A);
  if (!isFinite(dA)) throw new Error('could not probe Phase A duration for the xfade offset');
  // Head-trim: Playwright's very first painted frame can be a one-frame white flash
  // before the dark cover/SPA paints (≈0.7s in). Drop the opening HEAD_TRIM seconds —
  // beat 1's content only starts after the gotoClean reveal, so this is invisible but
  // kills the loop-seam flash. Merge it into the dead-air spans the cutter removes.
  const HEAD_TRIM = Number(process.env.HEAD_TRIM || 1.0);
  const deadAir = [[0, HEAD_TRIM], ...(deadAirIn || [])];
  const cut = deadAir.reduce((acc, [s, e]) => acc + Math.max(0, Math.min(dA, e) - Math.max(0, s)), 0);
  const aDur = Math.max(0, dA - cut); // Phase A length after cutting dead-air + head
  const offset = Math.max(0, aDur - XF);
  const {filter: aFilter} = phaseAFilter(dA, deadAir);
  const filterComplex =
    `${aFilter};` +
    `[1:v]${SCALE},${NORM}[b];` +
    `[a][b]xfade=transition=fade:duration=${XF}:offset=${offset.toFixed(3)},format=yuv420p[v]`;
  const args = [
    '-y',
    '-i',
    RAW_A,
    '-i',
    RAW_B,
    '-filter_complex',
    filterComplex,
    '-map',
    '[v]',
    '-an',
    '-c:v',
    'libvpx-vp9',
    '-b:v',
    '0',
    '-crf',
    '36',
    '-pix_fmt',
    'yuv420p',
    '-deadline',
    'good',
    '-cpu-used',
    '2',
    OUT_FILE,
  ];
  console.log(
    `[rig] ffmpeg stitch — Phase A ${dA.toFixed(1)}s − ${cut.toFixed(1)}s dead-air = ${aDur.toFixed(1)}s, xfade ${XF}s @ ${offset.toFixed(2)}s → ${OUT_FILE}`,
  );
  const r = spawnSync(HAS_FFMPEG, args, {encoding: 'utf8'});
  if (r.status !== 0) {
    console.log('[rig] ffmpeg stitch failed. stderr tail:');
    console.log((r.stderr || '').split('\n').slice(-14).join('\n'));
    throw new Error('ffmpeg stitch failed');
  }
}

/**
 * Fit the stitched clip INTO [TARGET_MIN_S, TARGET_MAX_S] with a mild uniform setpts
 * ramp: speed up (PTS/k, k>1) if over the ceiling, slow down (PTS*k) if under the
 * floor — each capped (MAX_SPEEDUP / MAX_SLOWDOWN) so it stays imperceptible. No-op
 * when already inside the band. Operates in place on OUT_FILE.
 */
function fitDuration() {
  const dur = ffprobeDuration(OUT_FILE);
  if (!isFinite(dur)) {
    console.log('[rig] could not probe duration — skipping duration-fit.');
    return;
  }
  if (dur >= TARGET_MIN_S && dur <= TARGET_MAX_S) {
    console.log(`[rig] duration ${dur.toFixed(1)}s within [${TARGET_MIN_S},${TARGET_MAX_S}]s band — no duration-fit needed.`);
    return;
  }

  // setpts expression + the resulting PTS multiplier (slowdown >1 lengthens).
  let ptsExpr;
  let mult; // playback-time multiplier applied to PTS
  let label;
  if (dur > TARGET_MAX_S) {
    const need = dur / (TARGET_MAX_S - 1); // aim ~1s under the ceiling
    const speed = Math.min(MAX_SPEEDUP, need);
    ptsExpr = `PTS/${speed.toFixed(4)}`;
    mult = 1 / speed;
    label = `speed-up ×${speed.toFixed(3)} (cap ${MAX_SPEEDUP})`;
  } else {
    // under the floor — stretch toward the band centre, capped.
    const mid = (TARGET_MIN_S + TARGET_MAX_S) / 2;
    const want = mid / dur; // desired stretch factor
    const stretch = Math.min(MAX_SLOWDOWN, want);
    ptsExpr = `PTS*${stretch.toFixed(4)}`;
    mult = stretch;
    label = `slow-down ×${stretch.toFixed(3)} (cap ${MAX_SLOWDOWN})`;
  }

  const tmp = resolve(RAW_DIR, 'fit.webm');
  const args = [
    '-y',
    '-i',
    OUT_FILE,
    '-an',
    '-vf',
    `setpts=${ptsExpr},fps=30`,
    '-c:v',
    'libvpx-vp9',
    '-b:v',
    '0',
    '-crf',
    '36',
    '-pix_fmt',
    'yuv420p',
    '-deadline',
    'good',
    '-cpu-used',
    '2',
    tmp,
  ];
  console.log(`[rig] duration ${dur.toFixed(1)}s outside [${TARGET_MIN_S},${TARGET_MAX_S}]s — ${label}`);
  const r = spawnSync(HAS_FFMPEG, args, {encoding: 'utf8'});
  if (r.status !== 0 || !existsSync(tmp)) {
    console.log('[rig] duration-fit failed; keeping the un-fit clip. stderr tail:');
    console.log((r.stderr || '').split('\n').slice(-10).join('\n'));
    return;
  }
  copyFileSync(tmp, OUT_FILE);
  rmSync(tmp, {force: true});
  const newDur = ffprobeDuration(OUT_FILE);
  if (isFinite(newDur) && (newDur > TARGET_MAX_S || newDur < TARGET_MIN_S)) {
    console.log(`[rig] NOTE: even at the cap the clip is ${newDur.toFixed(1)}s (target [${TARGET_MIN_S},${TARGET_MAX_S}]s). Adjust storyboard dwells for a closer-to-band source.`);
  }
}

// ───────────────────────────── main ─────────────────────────────
async function main() {
  console.log(`[rig] app=${APP} api=${API}`);
  console.log(`[rig] project=${PROJECT_ID}`);
  console.log(`[rig] ffmpeg: ${HAS_FFMPEG ? HAS_FFMPEG : 'NOT AVAILABLE'}`);
  if (!HAS_FFMPEG) {
    throw new Error('ffmpeg is required for the two-phase stitch. Install it (brew install ffmpeg) or set NO_FFMPEG=1 to ship Phase A only (debug).');
  }

  rmSync(RAW_DIR, {recursive: true, force: true});
  mkdirSync(RAW_DIR, {recursive: true});
  mkdirSync(OUT_DIR, {recursive: true});

  const token = await mintToken();
  console.log(`[rig] token minted (len=${token.length})`);

  const browser = await chromium.launch({headless: true});
  try {
    let runId = REUSE_RUN_ID;
    let deadAir = [];

    // PHASE A (unless explicitly skipped while reusing a run).
    if (!(REUSE_RUN_ID && SKIP_PHASE_A)) {
      const a = await recordPhaseA(browser, token);
      if (!REUSE_RUN_ID) runId = a.runId; // a fresh submit is the canonical run
      deadAir = a.deadAir || [];
    }
    if (!runId) throw new Error('no runId to verify (Phase A produced none and ES_RUN_ID unset).');

    // WAIT — block until the run is terminal (unless reusing an already-terminal run,
    // in which case this returns immediately).
    const {status} = await waitForRun(token, runId);
    console.log(`[rig] run ${runId} terminal: ${status}`);

    // PHASE B — verdict + code beats for the SAME run.
    await recordPhaseB(browser, token, runId);

    // STITCH (cutting Phase A's planning dead-air span).
    if (!existsSync(RAW_A) || !existsSync(RAW_B)) {
      throw new Error(`missing phase clip(s): A=${existsSync(RAW_A)} B=${existsSync(RAW_B)}`);
    }
    stitch(deadAir);

    // Fit the final clip to the duration budget (mild speed-up only if over).
    fitDuration();

    if (!process.env.KEEP_RAW) rmSync(RAW_DIR, {recursive: true, force: true});

    const finalSize = (statSync(OUT_FILE).size / 1e6).toFixed(2);
    const dur = ffprobeDuration(OUT_FILE);
    console.log('\n──────────────────────────────────────────────');
    console.log(`[rig] DONE → ${OUT_FILE}`);
    console.log(`[rig] runId=${runId}  finalStatus=${status}`);
    console.log(`[rig] size=${finalSize} MB   duration=${isFinite(dur) ? dur.toFixed(1) : '?'}s`);
    console.log('──────────────────────────────────────────────');
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error('[rig] FAILED:', e);
  process.exit(1);
});
