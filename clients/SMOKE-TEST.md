# Clients smoke test — CLI + VS Code

> A small end-to-end **functional** check for both clients against a **local** backend (~10 min).
> ("Does it build?" lives in [`VERIFY.md`](VERIFY.md); this is "does it work?".)

## 0. Prerequisites (once)

1. **Backend up** at `http://localhost:8080`:
   ```bash
   JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run
   ```
2. **Clients built + CLI linked** (from `clients/`):
   ```bash
   npm install
   npm run build -w @executespec/core && npm run build -w @executespec/cli
   npm link -w @executespec/cli          # puts `executespec` + `es` on PATH
   ```
3. **Point at the host:** `export EXECUTESPEC_HOST=http://localhost:8080`. Sign-in then happens two ways:
   - **Browser (default, gcloud-style):** `executespec auth login` opens the browser to
     `$EXECUTESPEC_HOST/cli-login`, you click **Authorize**, and the CLI captures a token over a
     one-shot loopback server. Needs the frontend served at that host — build it into the JAR's
     `static/` first: `npm --prefix frontend run build`.
   - **PAT fallback (headless / no frontend served):** mint one via the API and pass `--token`
     (needs `jq`):
     ```bash
     JWT=$(curl -s -XPOST $EXECUTESPEC_HOST/api/auth/login -H 'Content-Type: application/json' \
          -d '{"email":"YOU","password":"PW"}' | jq -r .accessToken)
     export PAT=$(curl -s -XPOST $EXECUTESPEC_HOST/api/auth/me/tokens -H "Authorization: Bearer $JWT" \
          -H 'Content-Type: application/json' -d '{"name":"smoke"}' | jq -r .token)   # es_pat_…
     ```

## A. CLI (~5 min)

| # | Step | Expect |
|---|---|---|
| A1 | `executespec auth login` (browser opens → **Authorize**) — or `auth login --token "$PAT"` / `--no-browser` | `Logged in as <you> (<tier>)` |
| A2 | `executespec auth status --json` | JSON `{email, tier, …}` — same identity |
| A3 | `executespec run list` | a runs table, or `No runs.` (exit 0) |
| A4 | `executespec run submit "add a /health endpoint returning 200 OK" --watch` | submits → streams `status`/`task`/`cost` → pauses with `Approve plan? [y/N]` |
| A5 | At the gate, press **`n`** (verifies plumbing without a full run) — or **`y`** to run it through to a PR | `n` → `Rejected.` + run CANCELLED · `y` → `COMPLETE` + PR URL |
| A6 | `executespec run view <runId> --json \| jq .status` | machine-readable detail (the scripting contract) |
| A7 | `executespec --host http://localhost:9 run list` | clean `can't reach server` one-liner, **non-zero exit**, no stack trace |

Notes: `es` is the short alias for every command · `auth logout` clears the token · **skip `run local`** — local-hands needs server-side activation that isn't shipped yet, so it's inert.

## B. VS Code extension (~5 min)

1. Build: `npm run build -w executespec-vscode` → emits `vscode/dist/extension.js`.
2. Open the **`clients/vscode`** folder in VS Code → press **F5** (launches the *Extension Development Host* window).
3. In the dev-host window: **Settings** → set `executespec.host` = `http://localhost:8080`.
4. **⌘⇧P → `ExecuteSpec: Sign In`** → browser opens `/cli-login?mode=vscode` → **Authorize** → redirects back to VS Code (`vscode://…/auth`) → notification: `signed in as <you>`. (Cancel the "waiting for browser sign-in" notification to fall back to pasting a PAT.)
5. **Explorer → "ExecuteSpec Runs"** view → your runs appear → click one → events stream into the **ExecuteSpec** OUTPUT channel.
6. **⌘⇧P → `ExecuteSpec: Submit Spec…`** → type a spec (or select text in the editor first) → optional repo URL → watch the OUTPUT channel stream live.
7. At the plan gate: a **modal** "Approve plan?" appears → **Reject** (plumbing-only) or **Approve** (full run → "Open PR" notification on finish).
8. **`ExecuteSpec: Sign Out`** clears the SecretStorage token.

## Pass criteria
- Both clients sign in via the **browser flow** (`/cli-login` mints a PAT, handed back over loopback / `vscode://`) — or the `--token`/paste fallback — and can list + submit + watch a run.
- The plan gate is a **deterministic** prompt (CLI `[y/N]`) / **modal** (VS Code) — never auto-approved.
- CLI `--json` emits parseable output; failures are clean one-liners with a non-zero exit.
- Live events render in both (CLI stdout / VS Code OUTPUT channel) and stop on a terminal event.

## Teardown
```bash
executespec auth logout
npm unlink -g @executespec/cli
# stop the backend (Ctrl-C)
```
