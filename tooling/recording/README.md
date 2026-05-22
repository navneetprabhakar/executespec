# Hero-video recording rig

Self-contained Playwright rig that records the annotated product-walkthrough video
used as the **hero video** on the marketing site (`website/index.html`, the
`<video src='/media/hero.webm' autoplay loop muted>` at the top of the page).

It drives the live VS Code-style **IDE** chrome (the only chrome) with a synthetic
animated cursor, click-pulse rings, and narration caption chips, and tells **one
coherent product story end-to-end**: a SINGLE fresh run against a SINGLE real
project (the _tictactoe_ project), threaded through every beat. Because a real run
takes minutes, the rig records in **two phases around the live execution** and
stitches them into one clip with ffmpeg.

> ⚠️ This rig **submits and approves a REAL run** — it clones the linked repo,
> builds, runs tests, and opens a real PR on `navneetprabhakar/tictactoe`. That is
> intended (the story shows a genuine end-to-end run). It costs LLM credits per
> recording. To re-render only the verdict beats from an already-finished run
> without submitting a new one, pass `ES_RUN_ID=<uuid> ES_SKIP_PHASE_A=1`.

Output: **`website/media/hero.webm`** (1280×800, VP9, ~55–65 s, loop-friendly).

## Files

| File              | Purpose                                                                                  |
| ----------------- | ---------------------------------------------------------------------------------------- |
| `record-hero.mjs` | The rig. Records Phase A + Phase B around a live run, then stitches with ffmpeg.         |
| `smoke.mjs`       | Fast auth + route probe (no recording). Run this first if a recording comes out blank.   |
| `diag.mjs`        | Captures one screenshot per surface to `/tmp/diag-*.png` (visual sanity check).          |
| `diag2.mjs`       | Probes the exact storyboard surfaces + selector counts (`/tmp/diag2-*.png`).             |
| `diag3.mjs`       | Dry-runs Phase B's verdict + code-diff locators against a terminal run (no submit).      |
| `package.json`    | Pins `playwright`. `npm run record` = `node record-hero.mjs`.                            |

## Prerequisites

1. **The app + API must be running locally:** frontend on `http://localhost:5173`,
   backend on `http://localhost:8080`. (The rig logs in via the API to mint a JWT.)
2. **The tictactoe project must exist + be linked + have an Architecture doc.**
   Default `ES_PROJECT_ID=6119f4b2-…` (Surface Test — tictactoe, repo
   `navneetprabhakar/tictactoe`). The login user must have a stored GitHub PAT
   (so the run can clone + open a PR) — `GET /api/auth/me` should show
   `hasGithubPat`.
3. **Install deps once:**
   ```bash
   cd tooling/recording
   npm install
   npx playwright install chromium
   ```
4. **ffmpeg** — **required** for the two-phase stitch. On macOS: `brew install ffmpeg`.
   (`NO_FFMPEG=1` ships Phase A's raw clip only — debug use; it cannot stitch.)

## Run

```bash
# from the repo root (paths are resolved relative to the script)
node tooling/recording/record-hero.mjs
# or
cd tooling/recording && npm run record
```

The rig prints per-beat timing marks, the submitted runId + its status transitions
while it polls, then the output path, size, and duration. It overwrites
`website/media/hero.webm` in place. It does **not** git-commit anything.

### Environment overrides (all optional)

| Var                          | Default                                  | Meaning                                                          |
| ---------------------------- | ---------------------------------------- | ---------------------------------------------------------------- |
| `APP_URL`                    | `http://localhost:5173`                  | Frontend origin.                                                 |
| `API_URL`                    | `http://localhost:8080`                  | Backend origin (used to mint the JWT + poll the run).            |
| `ES_EMAIL` / `ES_PASSWORD`   | `admin@executespec.ai` / (the dev admin) | Login used to mint the access token.                            |
| `ES_PROJECT_ID`              | `6119f4b2-…` (Surface Test — tictactoe)  | The single project the WHOLE story runs against.                |
| `ES_RUN_SPEC`                | `Add a hasWinner() helper …`             | The spec typed + submitted in beat 3.                           |
| `ES_RUN_ID`                  | unset                                    | Reuse an existing run for Phase B instead of submitting fresh.  |
| `ES_SKIP_PHASE_A`            | unset                                    | With `ES_RUN_ID`, set `1` to skip the submit/gate recording.    |
| `RUN_TIMEOUT_MS`             | `720000` (12 min)                        | How long the WAIT phase polls for the run to finish.            |
| `NO_FFMPEG`                  | unset                                    | Set to `1` to ship Phase A raw only (debug; no stitch).         |
| `KEEP_RAW`                   | unset                                    | Set to `1` to keep `.video-out/` (the raw phase clips).         |

## How it works

1. **Auth** — `POST /api/auth/login` → `{accessToken}` is injected into
   `localStorage.access_token` via `context.addInitScript` *before* any app JS runs,
   so the IDE loads authenticated. (`useAuthStore` reads that key.)
2. **Annotation overlay** — a second init-script (re-applied after every navigation)
   mounts `window.__rig`: a fixed-position SVG cursor that tweens (easeInOutCubic)
   to each target's centre, a click-pulse ring, a bottom-centre caption chip
   (JetBrains Mono, Signal palette), and a dark navigation cover that masks the
   route swaps so a hard navigation never flashes white.
3. **Phase A** (`recordPhaseA`) — records beats 1→5 with the `step({caption, target,
   action, …})` primitive (show caption, glide cursor to a locator, pulse, act,
   dwell). It **types + submits the spec**, polls until the run hits the plan gate
   (`AWAITING_APPROVAL`), shows the planned tasks on `/plan/{id}`, **clicks Approve**,
   then records a ~3 s glimpse of the executing state. Captures the `runId` from the
   `/live/{id}` URL. Hard-fails (no video shipped) if the run never reaches the gate.
4. **WAIT** (`waitForRun`) — not recording; polls `GET /api/runs/{runId}` every 6 s
   until the run is terminal (`COMPLETE`/`COMPLETED`/`PARTIAL`). Hard-fails on
   `FAILED`/`ERROR`/`CANCELLED` or on timeout — it will **not** ship a video missing
   the verdict beat.
5. **Phase B** (`recordPhaseB`) — a fresh recording context for the SAME `runId`:
   the verdict (Overview Signals — Build/Tests/Reviewer — + the score), then the
   **Code** tab (file tree with the real VS Code **Material Icon Theme** glyphs +
   the diff viewer, opening a generated source file), then back to `/home` for the
   loop seam.
6. **Stitch** (`stitch`) — ffmpeg `xfade` (0.4 s crossfade) joins Phase A → Phase B,
   re-encodes to VP9 (`-crf 36`, `fps=30`, even dims, muted) into `hero.webm`, then
   `ffprobe` reports the final duration.

## Storyboard beats (one continuous thread, ≈55–65 s, loops on `/home`)

| #   | Surface                                  | Caption                                                          | Action                                                  |
| --- | ---------------------------------------- | --------------------------------------------------------------- | ------------------------------------------------------- |
| 1   | `/home` (Workspace)                      | Ship PRs your team will actually merge.                          | Cursor glides across the Explorer tree.                 |
| 2   | `/projects/<id>/architecture`            | Point it at your real repo — it maps the architecture first.    | Scrolls the generated architecture doc.                 |
| 3   | `/projects/<id>` (Compose)               | Describe the change in plain English.                           | Types the spec into the box.                            |
| 4a  | …Submit → `/plan/<runId>`                | You approve the plan before a single token is spent.            | Clicks **Submit**, waits for the gate, shows the tasks. |
| 4b  | `/plan/<runId>`                          | Review the planned tasks — then approve.                        | Clicks **Approve** (charges credits, starts execution). |
| 5   | `/live/<runId>`                          | It codes across your repo, builds, and runs your tests.         | ~3 s glimpse of the executing state.                    |
| —   | _(WAIT — not recorded)_                  | —                                                               | Polls until the run is COMPLETE.                        |
| 6a  | `/result/<runId>` (Overview)             | Then a verdict you can trust — real signals, not an LLM's opinion. | Shows Signals (Build/Tests/Reviewer) + score.       |
| 6b  | …Code tab                                | Browse the real diff — every file, your stack's icons.          | Opens a source file → diff + Material file icons.       |
| 7   | `/home` (Workspace)                      | Plan · Build · Verify · Merge.                                  | Clean closing frame (loop seam).                        |

## Verify

```bash
ffmpeg -i website/media/hero.webm -vf fps=1/8 /tmp/hero2-frame-%02d.png   # sample frames
ffprobe -v error -show_entries format=duration -of csv=p=0 website/media/hero.webm
ls -lh website/media/hero.webm    # < 6 MB
```

## Troubleshooting

- **Blank / white video** → run `node smoke.mjs`. If it reports `REDIRECTED TO LOGIN`,
  the token didn't take (check the admin creds / that the backend is up). If a route
  selector warns, the IDE markup changed — update the locators in `record-hero.mjs`
  (and re-probe with `diag2.mjs` / `diag3.mjs`).
- **`PHASE A: run … never reached the plan gate`** → the planner stalled or the spec
  was classified as auto-execute. Check the run in the UI; confirm the project's
  Compose surface defaults to PRODUCTION (plan-gate) mode.
- **`run … ended FAILED`** or **`did not finish within …`** → the run failed or the
  12-min budget was too tight. Re-run; bump `RUN_TIMEOUT_MS`. The rig deliberately
  refuses to ship a video without a real verdict beat.
- **No PR opened** → the login user has no stored GitHub PAT, or the project's repo
  isn't linked. `GET /api/auth/me` should show `hasGithubPat`; the project must have
  a linked repo with push access.
- **Recording too long** → the per-beat timing marks show which beat overran; trim its
  `settle` / `hold`.
- **`playwright` not found** → run from `tooling/recording/` (or the repo-root path
  above) so Node resolves this dir's `node_modules`.
