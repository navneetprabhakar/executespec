# Hero-video recording rig

Self-contained Playwright rig that records the annotated product-walkthrough video
used as the **hero video** on the marketing site (`website/index.html`, the
`<video src='/media/hero.webm' autoplay loop muted>` at the top of the page).

It drives the live VS Code-style **IDE** chrome (the only chrome) with a synthetic
animated cursor, click-pulse rings, and narration caption chips over **existing demo
data** — it never submits a new run, so it never burns LLM cost.

Output: **`website/media/hero.webm`** (1280×800, VP9, ~40 s, loop-friendly).

## Files

| File              | Purpose                                                                                  |
| ----------------- | ---------------------------------------------------------------------------------------- |
| `record-hero.mjs` | The rig. Launches chromium, authenticates, runs the storyboard, normalises with ffmpeg.  |
| `smoke.mjs`       | Fast auth + route probe (no recording). Run this first if a recording comes out blank.   |
| `diag.mjs`        | Captures one screenshot per surface to `/tmp/diag-*.png` (visual sanity check).          |
| `package.json`    | Pins `playwright`. `npm run record` = `node record-hero.mjs`.                            |

## Prerequisites

1. **The app + API must be running locally:** frontend on `http://localhost:5173`,
   backend on `http://localhost:8080`. (The rig logs in via the API to mint a JWT.)
2. **Install deps once:**
   ```bash
   cd tooling/recording
   npm install
   npx playwright install chromium
   ```
3. **ffmpeg** (optional but recommended) — used to normalise the raw clip to a
   small, loop-friendly `.webm` and to extract verification frames. On macOS:
   `brew install ffmpeg`. Without it the rig ships Playwright's raw VP8 `.webm`
   as-is (larger; set `NO_FFMPEG=1` to force this path).

## Run

```bash
# from the repo root (paths are resolved relative to the script)
node tooling/recording/record-hero.mjs
# or
cd tooling/recording && npm run record
```

The rig prints per-step timing marks and finishes with the output path, size, and
duration. It overwrites `website/media/hero.webm` in place (the embed already points
there). It does **not** git-commit anything.

### Environment overrides (all optional)

| Var                          | Default                                  | Meaning                                              |
| ---------------------------- | ---------------------------------------- | ---------------------------------------------------- |
| `APP_URL`                    | `http://localhost:5173`                  | Frontend origin.                                     |
| `API_URL`                    | `http://localhost:8080`                  | Backend origin (used to mint the JWT).               |
| `ES_EMAIL` / `ES_PASSWORD`   | `admin@executespec.ai` / (the dev admin) | Login used to mint the access token.                 |
| `ES_RUN_ID`                  | `cd2d6807-…` (Library Management System) | Completed run shown on the Result + Code beat.       |
| `ES_PROJECT_ID`              | `6119f4b2-…` (Surface Test — tictactoe)  | Project shown on the Architecture beat.              |
| `MAX_DURATION`               | `46`                                     | Hard ceiling (s) ffmpeg trims to (safety net).       |
| `NO_FFMPEG`                  | unset                                    | Set to `1` to skip ffmpeg and ship the raw `.webm`.  |
| `KEEP_RAW`                   | unset                                    | Set to `1` to keep `.video-out/` (the raw clip).     |

## How it works

1. **Auth** — `POST /api/auth/login` → `{accessToken}` is injected into
   `localStorage.access_token` via `context.addInitScript` *before* any app JS runs,
   so the IDE loads authenticated. (`useAuthStore` reads that key.)
2. **Annotation overlay** — a second init-script (re-applied after every navigation)
   mounts `window.__rig`: a fixed-position SVG cursor that tweens (easeInOutCubic)
   to each target's centre, a click-pulse ring, a bottom-centre caption chip
   (JetBrains Mono, Signal palette), and a dark navigation cover that masks the
   route swaps so a hard navigation never flashes white.
3. **Storyboard** — driven by a `step({caption, target, action, …})` helper that
   shows the caption, glides the cursor to a Playwright locator, pulses, performs
   the action (click / type / scroll), and dwells for readability. Routes are
   pre-warmed on a throwaway page (its recording is discarded) so the on-camera
   `goto`s resolve from cache and the recorded wall-clock stays ≈40 s.
4. **Normalise** — ffmpeg re-encodes to VP9 (`-crf 37`, `fps=30`, even dims, muted),
   hard-capping the duration, then `ffprobe` reports the final duration.

## Storyboard beats (≈40 s, loops on the `/home` workspace frame)

| #   | Surface                          | Caption                                                     | Action                                              |
| --- | -------------------------------- | ----------------------------------------------------------- | --------------------------------------------------- |
| 1   | `/home` (Workspace)              | An agentic engineer that ships PRs.                         | Cursor glides across the Explorer tree.             |
| 2   | `/compose`                       | Describe the change in plain English.                       | Types a sample spec (no submit).                    |
| 3a  | `/result/<runId>`                | Every run ships a verified PR with a deterministic score.   | Shows the 94/100 score + Signals.                   |
| 3b  | …Code tab                        | Browse the real diff.                                       | Clicks **Code** → file tree + diff viewer.          |
| 4   | `/projects/<id>` → Architecture  | Auto-generated architecture, per repo.                      | Clicks **Architecture**, scrolls the arch doc.      |
| 5   | `/home` (Workspace)              | ExecuteSpec — plan · code · test · PR.                      | Clean closing frame (loop seam).                    |

## Troubleshooting

- **Blank / white video** → run `node smoke.mjs`. If it reports `REDIRECTED TO LOGIN`,
  the token didn't take (check the admin creds / that the backend is up). If a route
  selector warns, the IDE markup changed — update the locators in `record-hero.mjs`.
- **Recording too long** → the per-step timing marks show which beat overran; trim its
  `settle` / `hold`. `MAX_DURATION` is the hard ffmpeg ceiling.
- **`playwright` not found** → you're running from the wrong dir; run from
  `tooling/recording/` (or the repo root path above) so Node resolves this dir's
  `node_modules`.
