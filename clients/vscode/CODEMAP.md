# `executespec-vscode` — Codemap

> Per-file index of the ExecuteSpec VS Code extension. A **thin native client**: it renders runs in
> a TreeView, streams events to an OutputChannel, and approves the plan gate via a modal — all over
> the shared `@executespec/core` REST/SSE client. **No agent loop, prompts, or scoring here** — those
> stay server-side (*local hands / remote brain*). Companion: [`ARCHITECTURE.md`](ARCHITECTURE.md).

## File index

| File | LOC | Role |
| --- | --- | --- |
| `package.json` | 77 | `executespec-vscode@0.1.0`. **`contributes`**: 5 commands (`signIn`/`signOut`/`submitSpec`/`refreshRuns`/`watchRun`), a `executespecRuns` Explorer view, view-title menu buttons, and config (`executespec.host`, `executespec.defaultProvider`). `main`=`dist/extension.js`; engine `vscode ^1.85`; `build`=`node esbuild.mjs`. Dep `@executespec/core`. |
| `tsconfig.json` | 17 | NodeNext ESM, ES2022, `strict` + `noUncheckedIndexedAccess`. Typecheck only (esbuild does the emit). |
| `esbuild.mjs` | 15 | Bundler. `src/extension.ts` → `dist/extension.js` as **CJS** (VS Code loads CommonJS), bundling the ESM `@executespec/core` in, with **`vscode` left external** (host-provided). `node18` target, sourcemaps. |
| `src/extension.ts` | 95 | **Activation.** Creates the `ExecuteSpec` OutputChannel + `RunsTreeProvider`; registers the 5 commands + tree provider + a `registerUriHandler` (catches `vscode://…/auth`). `signIn` opens the browser to `/cli-login?mode=vscode`, awaits the `vscode://` token callback (state-validated; progress notification w/ cancel), then stores in SecretStorage + verifies `/auth/me`. Falls back to pasting a PAT. |
| `src/client.ts` | 16 | `makeClient(context)` → a core `ClientBundle` bound to the configured `executespec.host` + a `SecretTokenStore`. `defaultProvider()` reads the config. |
| `src/secretTokenStore.ts` | 21 | `TokenStore` backed by VS Code **`SecretStorage`** (encrypted) under key `executespec.pat`. The only secret the extension holds. |
| `src/runsTree.ts` | 57 | `RunsTreeProvider` (`TreeDataProvider<RunNode>`): lists the signed-in user's runs (`api.listRuns`, scope `mine`); a "Sign in…" node when no token; each `RunNode` shows spec + status, click → `watchRun`. `refresh()` fires the change event. |
| `src/watch.ts` | 144 | `submitSpec` (gather spec from selection/input + optional repo → `api.submitRun` → watch) and `watchRunInteractive`. The shared `watchRun` consumes `streamRunEvents`, appends `renderEvent` lines to the OutputChannel, handles `GATE_OPENED` via a **modal** (`handleGate`: Approve/Reject), and on terminal offers "Open PR". |
| `src/util.ts` | 38 | `errorMessage` (unwraps `ApiError`), `isTerminal(ev)`, and `renderEvent(ev)` → a one-line OutputChannel string (or null for noisy event types). |

## Module graph

```
extension.ts ─┬─ client.ts ──── secretTokenStore.ts ─┐
              ├─ runsTree.ts ──────────────────────────┤
              └─ watch.ts ──── util.ts ────────────────┴─→ @executespec/core
                                                            (ExecuteSpecApi · streamRunEvents ·
                                                             TokenStore contract)
  esbuild.mjs bundles extension.ts (+ core) → dist/extension.js (cjs), `vscode` external
```

## Command → behaviour map

| Command (palette / menu) | Behaviour |
| --- | --- |
| `ExecuteSpec: Sign In` | browser → `/cli-login?mode=vscode` → Authorize → `vscode://` token → SecretStorage → verify `/auth/me` (fallback: paste PAT) |
| `ExecuteSpec: Sign Out` | clear SecretStorage token → refresh tree |
| `ExecuteSpec: Submit Spec…` | spec from editor selection or input box → `submitRun` → stream to OutputChannel |
| `ExecuteSpec: Refresh Runs` | `RunsTreeProvider.refresh()` |
| `ExecuteSpec: Watch Run` | (tree click) stream a run's events; plan-gate modal; "Open PR" on finish |

## What is NOT here (by design)

- No agent loop, prompts, model routing, or scoring (server-side moat).
- No chat **webview** yet — current surface is native (TreeView + OutputChannel + modals). The Shape B
  chat sidebar is the fast-follow (see `ARCHITECTURE.md` §Roadmap + `ADR-0001`).
- No bundled `vscode` module (host-provided, marked external in esbuild).

## Build & run

```bash
npm install                              # workspace root (clients/)
npm run build -w @executespec/core       # core first
npm run build -w executespec-vscode      # esbuild → dist/extension.js
# F5 in VS Code (Extension Development Host), or: npx @vscode/vsce package → sideload the .vsix
```
