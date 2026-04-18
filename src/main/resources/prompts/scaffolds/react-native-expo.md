REACT_NATIVE_EXPO — complexity MEDIUM (6 files, ~120 lines):
{
  "task_id": "project_setup",
  "task_layer": "MOBILE",
  "title": "Create React Native + Expo project scaffold",
  "objective": "Write the scaffold so every other task has a typecheck-and-bundle-ready base. Create IN ORDER: 1) package.json with ALL dependencies (expo, react, react-native, typescript, @react-navigation/native + native-stack, jest-expo, @testing-library/react-native). Pin to the current stable Expo SDK. 2) app.json — Expo config with name, slug, icon/splash placeholders, ios.bundleIdentifier, android.package — both reverse-DNS. 3) eas.json — development / preview / production build profiles. 4) tsconfig.json extending expo/tsconfig.base. 5) babel.config.js with babel-preset-expo. 6) App.tsx — single root component rendering a NavigationContainer with a native-stack wrapping one placeholder screen. No per-screen files here — those belong in follow-up tasks.",
  "boundaries": "Only create package.json, app.json, eas.json, tsconfig.json, babel.config.js, App.tsx. Do NOT create screens/, components/, or assets/ binary files yet.",
  "output_format": "package.json + app.json + eas.json + tsconfig.json + babel.config.js at workspace root, App.tsx at workspace root (Expo convention — not src/).",
  "acceptance_criteria": "package.json has expo + react-native + typescript deps. app.json has expo.name, expo.slug, ios.bundleIdentifier, android.package. eas.json has development/preview/production profiles. App.tsx compiles under `npx tsc --noEmit`.",
  "depends_on": [],
  "complexity": "MEDIUM",
  "assigned_model": "{{WORKER_MODEL}}"
}

NOTE: Smoke command is `npx expo export --platform android --output-dir dist && npx tsc --noEmit` — metro bundles + types validate both platforms cheaply. No live preview inside our infra; preview is delivered as an Expo Go QR code the user scans on their phone. No native `android/` or `ios/` directories are emitted by v1 — managed Expo workflow only; `expo prebuild` is a later eject path.
