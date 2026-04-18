REACT_NATIVE_EXPO — complexity MEDIUM (7 files, ~140 lines):
{
  "task_id": "project_setup",
  "task_layer": "MOBILE",
  "title": "Create React Native + Expo project scaffold",
  "objective": "Write the scaffold so every other task has a typecheck-and-bundle-ready base. Create IN ORDER: 1) package.json with `\"main\": \"index.ts\"` and ALL dependencies (expo, react, react-native, hermes-compiler, expo-status-bar, @react-navigation/native + native-stack, react-native-screens, react-native-safe-area-context, typescript, @types/react, jest, jest-expo, @testing-library/react-native). Pin to the current stable Expo SDK (SDK 55.x at time of writing; check `npm info expo dist-tags` if in doubt). Include `hermes-compiler` as a direct dep — Expo SDK 55 / RN 0.80 under-declares it and bundling fails without it. Do NOT include `@testing-library/jest-native` — its matchers are built into @testing-library/react-native v12.4+. 2) app.json — Expo config with name, slug, icon/splash placeholders, ios.bundleIdentifier + android.package (both reverse-DNS). 3) eas.json — development / preview / production build profiles. 4) tsconfig.json extending expo/tsconfig.base with `\"strict\": true`. 5) babel.config.js with babel-preset-expo. 6) index.ts — one-liner `registerRootComponent(App)` entry (required because Expo's default entry resolution picks up this file via the main field). 7) App.tsx — single root component rendering a NavigationContainer with a native-stack wrapping one placeholder screen. Use `React.ReactElement` as the return type, NOT `JSX.Element` (React 19 dropped the global JSX namespace). No per-screen files here — those belong in follow-up tasks.",
  "boundaries": "Only create package.json, app.json, eas.json, tsconfig.json, babel.config.js, index.ts, App.tsx. Do NOT create screens/, components/, or assets/ binary files yet.",
  "output_format": "package.json + app.json + eas.json + tsconfig.json + babel.config.js + index.ts + App.tsx at workspace root (Expo convention — not src/).",
  "acceptance_criteria": "package.json has expo + react-native + hermes-compiler + typescript deps and `\"main\": \"index.ts\"`. app.json has expo.name, expo.slug, ios.bundleIdentifier, android.package. eas.json has development/preview/production profiles. App.tsx compiles under `npx tsc --noEmit` and `npx expo export --platform android --output-dir dist` produces a Metro-bundled .hbc file under dist/_expo/static/js/android/.",
  "depends_on": [],
  "complexity": "MEDIUM",
  "assigned_model": "{{WORKER_MODEL}}"
}

NOTE: Smoke command is `npx expo export --platform android --output-dir dist && npx tsc --noEmit` — Metro bundles + types validate both platforms cheaply. No live preview inside our infra; preview is delivered as an Expo Go QR code the user scans on their phone. No native `android/` or `ios/` directories are emitted by v1 — managed Expo workflow only; `expo prebuild` is a later eject path.

Reference scaffold lives at `scripts/rn-smoke/` — npm-installs and smokes green as of 2026-04-18. Re-run `npm install && npm run smoke` there after any SDK bump to catch dep-resolution regressions before they hit users.
