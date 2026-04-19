# Mobile Code Generation Plan (React Native + Expo)

> Status: **design draft** — 2026-04-18 (rev 2, pivoted from Kotlin+Compose to React Native + Expo so the same codebase emits Android **and** iOS). Feature-flagged so web is unaffected.

## Decision: React Native + Expo (managed workflow) + TypeScript

Rationale:
- Single codebase → both platforms (Android + iOS). This was the user's explicit goal for rev 2.
- TypeScript + JSX keeps the planner and worker in the same mental model they already use for the `node-frontend` scaffold (React on web) — reduces per-platform prompt engineering cost.
- **Expo (managed workflow)** over bare RN: gets us (1) `expo-cli export` for deterministic bundling, (2) Expo Go QR-preview on a physical device without any iOS/Android SDK install, (3) **EAS Build** as a hosted builder for signed APK/IPA — critical because iOS native builds cannot run on Linux Cloud Run, so hosted builds sidestep the macOS-runner problem entirely.
- Bare-RN eject is a single `npx expo prebuild` away if a project outgrows managed mode. Starting Expo keeps the v1 surface small.

Explicitly out of scope for v1:
- Bare RN + React Native CLI (no `react-native init`).
- Native module authoring (Kotlin/Swift inside `android/` or `ios/`).
- Kotlin Multiplatform / SwiftUI / Jetpack Compose paths — RN covers both targets with one prompt.
- On-device preview via emulator-in-container (Appium etc.).
- Play Store / App Store direct upload (ships as signed artifact only in v1).

## Planner changes

### New `project_type` enum value
```
REACT_NATIVE_EXPO
```
Wired through:
- `PlannedTaskV2.projectType` enum (currently 8 values in `prompts/v2/planner.md:82`; add `react-native-expo`).
- `PreviewProjectType` — new value `REACT_NATIVE_EXPO`; preview path routes to the Expo-Go QR-pairing flow (see Preview).
- `Deployment.DeployStrategy` — add `REACT_NATIVE` strategy (produces APK + IPA via EAS, uploads to GCS; no Cloud Run).
- `DockerfileGenerator` / `DevDockerfileGenerator` — for **local smoke builds** (type-check + bundle validation) we can reuse the existing Node 20 image. Real signed builds happen in EAS, not in our Docker image.

### New `runtime_surface` value
```
MOBILE_APP
```
Added to the existing enum in `PlanV2` (joins `HTTP` / `CLI` / `LIBRARY` / `STATIC_WEB`). Test planner uses it to generate Jest + React Native Testing Library tests (not Compose UI tests).

### New scaffold template
`resources/prompts/scaffolds/react-native-expo.md` — minimum viable baseline:
```
app.json                 # Expo config (name, slug, icon, splash, ios.bundleIdentifier, android.package)
package.json             # expo, react, react-native, typescript, jest-expo, RN-testing-library
tsconfig.json            # extends expo/tsconfig.base
metro.config.js
babel.config.js
eas.json                 # development / preview / production profiles
App.tsx                  # root component
src/
  navigation/            # @react-navigation/native + native-stack
  screens/
  components/
  theme/
  api/                   # fetch wrappers
__tests__/               # Jest + RN Testing Library smoke
assets/
  icon.png splash.png
```

### New planner-constraint block
In `planner.md`, under "TASK COMPOSITION", add a branch for `react-native-expo`:
- Task 1: scaffold (app.json, package.json, eas.json, App.tsx, theme).
- Task 2: navigation skeleton (routes, stack nav, placeholder screens).
- Task 3+: per-screen `.tsx` Composable files.
- Each task declares `ownedFiles` pinned to a single `.tsx` / `.ts` file to respect the existing subtask-decomposition rules.
- `buildCommand`: `npx expo export --platform android --output-dir dist && npx tsc --noEmit` (cheap, validates both bundle + types).
- `smokeTestStrategy`: `BUILD` (successful export + typecheck is the smoke test; no live preview inside our infra).

## Build / smoke test

- `BuildCommandResolver` gains a rule: if `app.json` exists AND `package.json` depends on `expo`, emit `npx expo export --platform android --output-dir dist && npx tsc --noEmit`. (Android-only export is sufficient — iOS bundling is a Metro variant and Expo will catch the same errors.)
- Workspace ships a lockfile (`package-lock.json`) so `npm ci` is reproducible inside the smoke container.
- Local smoke runs in a container with:
  ```
  node:20-alpine
  + preinstalled expo-cli (global)
  ```
  Reuse the existing Node image used by `node-frontend` scaffolds — no new base image needed for v1.
- `ShellSandboxPolicy` extensions required:
  - `npm` verbs: `ci` already allowed; add `exec` for `npx` (or add `npx` as a separate top-level verb). 
  - New top-level verb family `expo` with allowlist `{export, prebuild, install, doctor}`.
  - New top-level verb family `eas` with allowlist `{build, submit, update}` (gated behind the deploy path only).

## Preview story (v1: Expo Go QR pairing)

Cloud Run can't render an RN app, but Expo Go can — on the user's physical phone:
- On plan approval, executor runs `npx expo export` inside the workspace. The bundle + manifest is served from our backend under `/api/runs/{id}/expo/manifest`.
- Frontend renders a QR code that the user scans with Expo Go (installed from Play Store / App Store). The phone pulls the bundle and renders the app live.
- Works on both Android and iOS with **zero signing** — Expo Go is already signed by Expo.
- Limitations called out in the UI: (a) native modules outside the Expo SDK won't work in Expo Go → user needs a dev-client build (EAS path, below); (b) Expo Go has a fixed SDK version, so the scaffold pins to the matching Expo SDK.

Future v2: in-browser preview via `react-native-web` + the existing Cloud Run preview pipeline. Most Compose-less, pure-RN screens render fine on web with minimal code changes; defer until v1 stabilises.

## Deploy story (v1: EAS Build → GCS)

`DeploymentService` gains a `REACT_NATIVE` branch:
- Reads `deploy.target` enum on the run (`ANDROID | IOS | BOTH`).
- Invokes EAS Build via the EAS CLI (`eas build --platform <t> --profile production --non-interactive --no-wait`) using an org-scoped EAS access token stored encrypted (`org.eas_token`, new V118 migration).
- Polls EAS REST API for build completion; on SUCCESS, downloads the signed artifact (APK/AAB for Android, IPA for iOS) and re-uploads to GCS at `gs://executespec-mobile-builds/<orgId>/<runId>/<platform>.<ext>`.
- Returns a 24h-signed download URL + the original EAS artifact URL (belt + suspenders so users can fetch directly from EAS).
- iOS signing: user uploads `.p12` + provisioning profile to EAS once via their own EAS dashboard (out of our scope — documented in user manual). Reduces our secret-custody surface.
- Android signing: user uploads upload keystore to EAS once (same pattern).

Explicitly **not** shipping in v1:
- Direct Play Store / App Store submission (`eas submit`). Put on roadmap — needs per-org service-account key management + track/rollout UX.
- Self-hosted EAS (would need a macOS runner for iOS). Hosted EAS is paid per build; factor into pricing model (see Open Questions).

## Feature flag

```
codegen.react_native.enabled  (boolean, default false)
```
Gates:
- Planner accepts `project_type = react-native-expo` (else 501).
- UI project-selector shows the "Mobile (React Native)" option.
- Deployment accepts the `REACT_NATIVE` strategy.

Seed via `V118__seed_rn_codegen_flag.sql`. Suggested rollout: internal dogfood → closed beta for 2-3 orgs → general.

## Test strategy hooks

- `runtime_surface: MOBILE_APP`.
- Test planner generates:
  - Jest + `@testing-library/react-native` component tests for declared screens / components.
  - `expo-doctor` as a static-analysis gate (catches SDK misconfig, missing icons, etc.).
  - Optional: `eas build --profile preview --platform android --local=false --no-wait` to validate the preview profile compiles end-to-end in EAS (opt-in — costs an EAS build credit).
- Executor runs tests via `npx jest --ci` inside the same Node smoke image. Capped at 10 tests per v1 to keep cycle time reasonable.
- Connected device tests (Detox / Maestro) deferred to v2 — needs an emulator or device-farm story.

## Stack detection

`StackDetector.java` gains a rule: if `app.json` exists at repo root **and** `package.json` has `"expo"` in `dependencies`, tag as `REACT_NATIVE_EXPO`. Order matters — this rule must run **before** the generic Node rule or RN repos get misclassified as `node-frontend`.

## Guardrails mapping

- `ShellSandboxPolicy` — at `LOW` nothing changes. At `MEDIUM+` the new `expo` / `eas` / `npx` verb allowlists activate (see Build section). `HIGH` restricts `eas` to `build` + `submit` only (no `update`, which is EAS's OTA push — powerful, deploy-equivalent).
- `FileSandboxPolicy` — needs `android/` + `ios/` in the `MEDIUM_ALLOWED_DIRS` set for post-prebuild workflows. Add `assets/` (already there), `__tests__/`.
- `CodeAbuseScanner` — no changes. JS/TS is already in scope.

## Open questions

- **Expo SDK pin**: SDK 52 (latest stable as of 2026-04) unless React Native 0.76+ forces a bump. Pin in scaffold, rev quarterly.
- **EAS billing**: Expo's hosted build minutes cost real money (~$0.04/min). Options: (a) pass through at cost, (b) absorb inside PRO/MAX tiers with a quota, (c) BYO-EAS-token (user's own EAS account). **Decision deferred** until we've run RN eval specs end-to-end and can measure (i) LLM token consumption per mobile project vs web, (ii) EAS-minute spend per size bucket. Ship v1 with (c) as the stopgap (zero credit-card surface for us) and revisit the pricing model once data is in.
- **Secret storage for `.p12` / keystore**: Not ours to hold in v1 — live entirely in EAS. If we need to hold them later, Cloud KMS (same as planned for the Kotlin rev, just reused).
- **Bundle-size budget**: warn in the ValidationReportPanel if the Metro bundle exceeds 8 MB gzipped (typical RN baseline is ~2 MB; >8 MB usually means a huge dep got pulled in by mistake).
- **iOS runtime-surface gate**: do we require the user to have an Apple Developer account before accepting a REACT_NATIVE run with `target: BOTH`? Recommend yes — fail fast in the pre-plan classifier rather than surprising them at EAS-build time.

## Metrics
- `executespec.codegen.rn.build.duration{platform}` (android | ios | both)
- `executespec.codegen.rn.bundle.size_bytes{platform}`
- `executespec.codegen.rn.eas.minutes_used{platform,profile}`
- `executespec.codegen.rn.expo_doctor.findings{severity}`
- `executespec.codegen.rn.task.outcome{status}`

## Implementation checklist (in order)

1. **Scaffold + prompt** — `prompts/scaffolds/react-native-expo.md` + add `react-native-expo` to the planner's `projectType` enum + sample spec under `scripts/v2-eval-specs/mobile.txt`.
2. **Stack detection** — `StackDetector` + `BuildCommandResolver` rules, unit tests.
3. **ShellSandboxPolicy** — new verb families (`expo`, `eas`, `npx`) with MEDIUM/HIGH tightening + tests.
4. **Feature flag** — V118 migration seeds `codegen.react_native.enabled=false`; gate planner, deploy, UI.
5. **Smoke path** — reuse Node 20 image; verify `npx expo export` + `tsc --noEmit` green on the scaffold.
6. **Preview** — `/api/runs/{id}/expo/manifest` endpoint + QR code component.
7. **Deploy** — `REACT_NATIVE` deploy strategy + EAS Build client + GCS upload.
8. **Tests** — Jest + RN Testing Library scaffold tests; test planner `MOBILE_APP` surface.
9. **Docs** — user manual page for Expo Go install + EAS account setup.
10. **Eval** — add a fourth size bucket `mobile` to `scripts/v2-eval-strict.sh`.

Legacy Kotlin + Compose design (rev 1) superseded by this rev. If we ever need deep native code, eject to bare RN (still RN semantics) before going Kotlin — that's a smaller jump than two native code paths.
