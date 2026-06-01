# `executespec-vscode` — Architecture

> The ExecuteSpec VS Code extension. Companion: [`CODEMAP.md`](CODEMAP.md). Design of record:
> `ADR-0001-unified-spec-client` + `cli-vscode-unified-client-2026-05-30` (main repo `docs/`).

## 1. Purpose & principles

An in-editor client for the ExecuteSpec pipeline, on the same **local hands / remote brain** model
as the CLI:

- The **brain stays server-side** (classifier → planner → worker → reviewer, prompts, routing,
  scoring). The extension embeds none of it.
- The extension is **rendering + host integration**: submit a spec, watch runs, approve the plan
  gate — using VS Code-native surfaces.
- It is a **sibling renderer** to the CLI over the *same* `@executespec/core`. Feature parity
  between the two is the product invariant (`ADR-0001`).

This is a **native MVP**: TreeView + OutputChannel + modals. The richer chat sidebar is the roadmap
(§7), not yet built.

## 2. Components & activation

```
activate(context)                         (extension.ts)
  ├─ OutputChannel "ExecuteSpec"          (live run stream)
  ├─ RunsTreeProvider  → Explorer view "ExecuteSpec Runs"
  └─ registerCommand × 5: signIn · signOut · submitSpec · refreshRuns · watchRun
        │
        ├─ client.ts  makeClient(context) ── secretTokenStore.ts (SecretStorage PAT)
        ├─ runsTree.ts  list runs (api.listRuns)
        └─ watch.ts  submit + stream + plan-gate modal ── util.ts (render/terminal)
                                   │
                                   └─→ @executespec/core (REST + SSE)
                                            │ HTTPS + SSE (PAT bearer)
                                    ExecuteSpec backend (unchanged pipeline)
```

Everything is registered into `context.subscriptions` for clean disposal; `deactivate()` is a no-op.

## 3. Configuration & auth

- **Settings** (`contributes.configuration`): `executespec.host` (default `https://executespec.ai`;
  set to `http://localhost:8080` for dev) and `executespec.defaultProvider` (`ANTHROPIC` /
  `OPENAI` / `GEMINI`).
- **Sign-in: gcloud-style browser flow (default).** `Sign In` opens the browser to
  `${host}/cli-login?mode=vscode&state=`; the web page mints a PAT and redirects to
  `vscode://executespec.executespec-vscode/auth?token=&state=`, caught by the extension's
  `registerUriHandler` (state-validated). Cancel the "waiting for browser sign-in" notification to
  fall back to pasting a PAT. The token is stored in VS Code **`SecretStorage`** (encrypted) via
  `SecretTokenStore` under key `executespec.pat`, verified via `/auth/me`. It's the only secret
  held; sent as `Authorization: Bearer es_pat_…`, it resolves to the same identity a JWT would.

## 4. Data flow

Same transport as the CLI — **REST for actions, SSE for streams, no WebSocket** — because both ride
the shared `@executespec/core`:

**Submit + watch a run:**
```
Submit Spec… ─POST /api/runs──────────▶ backend
             ◀── 202 {runId} ──────────
watchRun =GET /runs/{id}/events (SSE)═▶
  ◀≈≈ status / task / file / cost ≈≈   → OutputChannel lines (util.renderEvent)
  ◀≈≈ GATE_OPENED ≈≈  →  showInformationMessage({modal:true}, Approve, Reject)
                        Approve → POST /runs/{id}/approve
  ◀≈≈ COMPLETE ≈≈      → "Open PR" notification (scmPrUrl / githubPrUrl)
```
`streamRunEvents` (in core) mints a fresh 5-min scoped SSE token per connection and reconnects with
`Last-Event-ID`, so a long run survives token expiry transparently.

**Local-hands** (future, shared with the CLI): the extension would drive the same `ToolRuntime` /
`LocalExecutionSession` from core against the open workspace folder. The server-side activation
wiring isn't shipped yet, so this is not active in the MVP.

## 5. Governance

The plan gate is a **deterministic, native modal** (`handleGate` → `showInformationMessage` with
`{modal:true}`, Approve/Reject) — not an LLM-mediated action. Dismissing the modal leaves the gate
open to approve later from the Runs view.

## 6. What is NOT here

- No agent loop / prompts / scoring (server-side moat).
- No chat webview yet (native MVP). No `vscode` module bundled (host-provided, external in esbuild).
- No durable server-side state; PAT lives only in SecretStorage.

## 7. Build, package, distribute

```bash
npm install                              # workspace root (clients/)
npm run build -w @executespec/core       # core first
npm run build -w executespec-vscode      # esbuild → dist/extension.js (CJS, core bundled)
# F5 → Extension Development Host, or `npx @vscode/vsce package` → sideload the .vsix
```
Distribution: sideload the VSIX initially; VS Code Marketplace at the polish phase (`ADR-0001` #5).
`@executespec/core` is bundled into `dist/extension.js`, so the only external at runtime is the
host-provided `vscode` module.

## 8. Roadmap (Shape B)

Per `ADR-0001`, the extension grows a **chat sidebar webview** (`chatPanel.ts` + `chatPanel.html`)
that mirrors the CLI's chat REPL over `POST /api/spec/chat`, while **keeping** the TreeView + the
native plan-gate modal as the at-a-glance + governance surface. Parity with the CLI is enforced:
every capability ships to both clients.
