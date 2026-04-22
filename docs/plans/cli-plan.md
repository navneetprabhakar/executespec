# ExecuteSpec CLI — plan stub

> **Status:** DEFERRED to external repo. This doc exists so the in-repo
> API stays aligned with what the CLI will consume.
> **Repo:** `executespec-cli` (to be created under `navneetprabhakar` org).
> **Wave:** 6 (2026-07-02 → 2026-07-15).
> **Origin:** [persona-roadmap-2026-04-22.md §5.6 W6.1](persona-roadmap-2026-04-22.md).

## v1 commands

```bash
executespec login                          # OAuth device-code
executespec submit "add POST /api/foo"     # from CWD
executespec submit --file spec.md          # read spec from file
executespec status <runId>                 # one-shot status print
executespec status <runId> --watch         # tail until terminal
executespec logs <runId>                   # full history
executespec logs <runId> --tail -f         # SSE stream
executespec pr <runId>                     # opens PR URL
executespec logout                         # clear cached token
executespec version
```

## Tech stack

- Node.js 20+ (Homebrew + npm distribution)
- `commander` — CLI framework
- `ora` — spinners
- `chalk` — coloured output
- `undici` — HTTP + SSE client
- `conf` — credential cache in `~/.executespec/config.json`

## Authentication

Device-code flow (shared with [VS Code extension](vscode-extension-plan.md)):

```
$ executespec login
Open https://app.executespec.ai/device to authorize:
    Code: JZNK-9WF2

Waiting... ✓ Authorized.
Logged in as navneet@example.com.
```

Backend contract (implemented in this monorepo when Wave 6 starts):
- `POST /api/auth/device-code/start` → returns `{ userCode, verificationUrl, expiresIn, interval }`
- `POST /api/auth/device-code/poll` → polls; returns `{ status: 'pending' }` or `{ accessToken, refreshToken, user }`
- Rate-limit: polling interval enforced server-side at 5s

## Submit flow

```
$ cd my-repo
$ executespec submit "add POST /api/contacts with validation"
→ Detecting git remote... origin = github.com/navneet/my-repo
→ Submitting run to app.executespec.ai...
✓ Run submitted: run_7f2a9b
→ Plan ready — review at https://app.executespec.ai/runs/run_7f2a9b
  (or use `executespec status run_7f2a9b --watch`)
```

Source: `source=cli`, `repoUrl=<git remote>`, `repoMode=EXISTING`. If git
remote is missing, falls back to `repoMode=NONE` + prompts for confirmation.

## Output conventions

- Success: `✓ green`
- Warning / attention needed: `! amber`
- Error: `✗ red`
- In-progress: animated spinner
- JSON output: `--json` flag on all commands for scripting

## Distribution

- NPM: `@executespec/cli` — global install via `npm install -g`
- Homebrew: `brew install executespec/tap/cli` (separate tap repo)
- Standalone binary: `pkg` build for macOS / Linux / Windows (v2)

## Timeline (external)

- Day 1-2: scaffold + `commander` wiring + config cache
- Day 3: device-code flow + login/logout
- Day 4-5: submit + status + logs
- Day 6-7: docs + README + publish to NPM
- Day 8-10: Homebrew tap + issue triage from early adopters

## Anti-scope

- No local run execution (runs happen on our infrastructure)
- No inline code suggestions (Cursor / Copilot territory)
- No chat interface (web app only)
- No plan editing (web app only)

## Success criteria

- [ ] `npm install -g @executespec/cli` works on Node 20+ macOS + Linux
- [ ] `executespec login` completes device-code flow in under 60s
- [ ] `executespec submit` from a git-initialized repo triggers a real run
      against `app.executespec.ai`
- [ ] `executespec status --watch` shows live progress with correct
      terminal rendering
- [ ] Published to NPM as `@executespec/cli` v0.1.0 within the Wave 6 window
