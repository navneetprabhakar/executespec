# Android Code Generation Plan

> Status: **design draft** — 2026-04-12. Adds Android/Kotlin/Compose as a supported output target, feature-flagged so web is unaffected.

## Decision: Kotlin + Jetpack Compose + Gradle Kotlin DSL

Rationale:
- Single language across build (Gradle KDSL), app code, and tests.
- Compose is Google's strategic UI — fewer XML + Activity/Fragment abstractions for the planner to juggle.
- Smaller scaffolding surface than legacy View/XML; more of the app lives in one file tree the worker can reason about.
- Easier template system for the planner (one scaffold, not two).

Explicitly out of scope for v1:
- Java / XML layouts (no greenfield demand).
- KMP (Kotlin Multiplatform) — revisit once Android is stable.
- Instant Run / Live Edit — preview story below is simpler.

## Planner changes

### New `project_type` enum value
```
ANDROID_KOTLIN_COMPOSE
```
Wired through:
- `PreviewProjectType` (skip preview for now — see Preview below)
- `Deployment.DeployStrategy` — add `ANDROID` strategy (produces APK artifact, no Cloud Run).
- `DockerfileGenerator` / `DevDockerfileGenerator` — for builds, emit a Docker image with Android SDK command-line tools (`cmdline-tools` + `platform-tools` + `build-tools;34.0.0`).

### New scaffold template
`resources/prompts/scaffolds/android-kotlin.md` — minimum viable baseline:
```
app/
  build.gradle.kts           # applies com.android.application + kotlin.android + compose
  src/main/
    AndroidManifest.xml
    java/com/<pkg>/
      MainActivity.kt        # single-activity Compose host
      ui/
        theme/Theme.kt
        screens/
    res/
      values/strings.xml
  proguard-rules.pro
build.gradle.kts             # root — Android plugin aliases + Kotlin version
settings.gradle.kts
gradle.properties            # androidx + jvm args
```

### New planner-constraint block
In `planner.md`, under "TASK COMPOSITION", add a branch for `ANDROID_KOTLIN_COMPOSE`:
- Task 1: scaffold (build files + MainActivity).
- Task 2+: per-screen Composable files.
- Each task declares `ownedFiles` pinned to a single `.kt` file to stay within the subtask-decomposition rules already in place.
- `buildCommand`: `./gradlew :app:assembleDebug --no-daemon --quiet`.
- `smokeTestStrategy`: `BUILD` (APK assembly is the smoke test; no preview available).

## Build / smoke test

- New `BuildCommandResolver` entry that recognizes `build.gradle.kts` with `com.android.application` plugin and returns `./gradlew assembleDebug`.
- Workspace ships a `gradle/` wrapper so no host Gradle install is needed; agent downloads on first build (cached in `~/.gradle`).
- Build runs in a container with:
  ```
  mcr.microsoft.com/openjdk/jdk:21-ubuntu
  + ANDROID_SDK_ROOT=/opt/android-sdk
  + cmdline-tools;latest, platform-tools, platforms;android-34, build-tools;34.0.0
  ```
  Pre-built and pushed once to Artifact Registry; tagged `executespec-android-builder:jdk21-sdk34`.

## Preview story (v1: none)

- Cloud Run can't run Android UI. Options considered:
  - Headless emulator in a container + Appium + screenshot streaming → complex, slow, expensive.
  - Compose-for-Web build — works but changes the framework target; not a faithful preview.
- **v1 decision: no live preview.** Deliverables are:
  - Compiled `app-debug.apk` downloadable from the run workspace.
  - `./gradlew lint` report rendered in the ValidationReportPanel.
  - Unit tests run under `./gradlew testDebugUnitTest` and exposed through the existing test panel.
- Future v2 preview: QR-code pairing to a user's physical device + the APK served over HTTPS for sideload. Simpler than emulator-in-container, authentic experience.

## Deploy story (v1: APK artifact only)

- `DeploymentService` gains an `ANDROID` branch that:
  - Builds the release variant (`assembleRelease`) with a user-supplied keystore (stored encrypted per org, reused across runs).
  - Uploads the signed APK to GCS: `gs://executespec-android-builds/<orgId>/<runId>.apk`.
  - Returns a 24h-signed download URL.
- Explicitly **not** shipping direct Play Store upload in v1 — it needs org-level service account key management + track/rollout logic. Put on roadmap.

## Feature flag

```
codegen.android.enabled  (boolean, default false)
```
Gates:
- Planner accepts `project_type = ANDROID_KOTLIN_COMPOSE` (else 501).
- UI project-selector shows the "Android" option.
- Deployment accepts the `ANDROID` strategy.

Suggested rollout: internal dogfood → closed beta for 2-3 orgs → general.

## Test strategy hooks

Integrates with the separate "CLI + library runtime_surface" plan (`docs/runtime-surface-plan.md` — pending). For Android:
- `runtime_surface: MOBILE_APP`
- Test planner generates Compose UI tests (`androidx.compose.ui.test`) for declared Composables.
- Executor runs `./gradlew connectedAndroidTest` inside the same Android builder image (with `emulator -no-window` for instrumentation runs). Capped at 2 tests per v1 to keep cycle time reasonable; expand later.

## Open questions

- **Kotlin version pin**: follow Compose BOM's recommended Kotlin (currently 2.0.21 → Compose Compiler 2.0.21). Rev quarterly.
- **Min SDK**: 24 (Android 7.0) — covers 97% of active devices, drops a lot of compat shims.
- **Signing key storage**: Cloud KMS vs pgcrypto. Lean KMS — audit trail.
- **Dependency set**: start with AndroidX core, Compose BOM, Coroutines, Hilt (optional), Retrofit. Avoid Jetpack Navigation for v1 — Compose's own nav-compose is lighter.

## Metrics
- `executespec.codegen.android.build.duration`
- `executespec.codegen.android.apk.size_bytes`
- `executespec.codegen.android.lint.findings{severity}`
- `executespec.codegen.android.task.outcome{status}`
