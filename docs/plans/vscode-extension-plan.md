# VS Code extension — plan stub

> **Status:** DEFERRED to external repo. This doc captures scope + integration
> contract so the in-repo ExecuteSpec API stays aligned with what the
> extension will consume.
> **Repo:** `executespec-vscode` (to be created under the `navneetprabhakar`
> org). Not part of this monorepo — ships via VS Code Marketplace.
> **Origin:** [persona-roadmap-2026-04-22.md §5.4 W4.5](persona-roadmap-2026-04-22.md).
> **Last updated:** 2026-04-23.

## v1 feature set

Three commands. Thin by design — the extension is a surface, not a
reimplementation.

| Command | Behavior |
|---|---|
| `ExecuteSpec: Submit spec` | Opens a webview with spec input. Targets the current workspace folder's git repo. Submits to `POST /api/runs` with `source=vscode`, `repoUrl=<git remote>`. |
| `ExecuteSpec: Show last run` | Opens a webview with a read-only run detail view. Polls `GET /api/runs/{id}` + SSE for live updates. |
| `ExecuteSpec: Open verified report` | Launches the verified-report PDF in the OS default PDF viewer via `child_process.exec(open <url>)`. |

## Authentication

OAuth device-code flow. Extension posts to a new
`POST /api/auth/device-code/start` endpoint on the backend — returns
a short user code + verification URL. User opens the URL in a browser,
grants the extension access, extension polls `POST /api/auth/device-code/poll`
until the JWT is issued.

**Backend work (in this monorepo, when extension starts):**
- `DeviceCodeController` + `DeviceCodeService`
- DB table `oauth_device_code` (8-char user code, expires in 15 min,
  one-shot)
- Issued JWTs carry `clientType=vscode` claim for audit

## Why a separate repo

- VS Code extensions ship via the VS Code Marketplace, not our CI/CD.
  Separate repo means separate release cadence + versioning.
- TypeScript + `vscode` API — no shared build infra with the web app.
- Issue tracking can live on GitHub Issues for the extension repo,
  keeping the monorepo's issue list focused on platform work.

## Anti-scope

- **No local preview.** Preview runs on our Cloud Run / preview-engine
  infrastructure; extension just links to it.
- **No inline code suggestions.** That's Cursor / Copilot territory;
  ExecuteSpec is spec-first.
- **No AI chat.** Chat lives in the web app; extension is for
  run-submission + results-viewing only.

## Timeline

When Wave 4 starts (2026-06-04), spin up `executespec-vscode` repo,
implement v1 in 3-5 days, publish to Marketplace. Device-code endpoints
get added to the backend monorepo at that point.

## Success criteria

- [ ] Extension installable from VS Code Marketplace under the name
      "ExecuteSpec"
- [ ] Three commands visible in the command palette after install
- [ ] OAuth device-code flow completes on first use; subsequent invocations
      reuse cached token
- [ ] Running `ExecuteSpec: Submit spec` from a git-initialized workspace
      submits a real run to `app.executespec.ai`

## Non-goals

- SSO integration (v1 uses OAuth device-code — works for MAX tier SSO
  users because the underlying JWT flow is the same)
- Multi-workspace support (v1 = one workspace = one run at a time)
- Status bar run indicator (Wave 5 polish — v1 ships without)
