# RN Smoke Scaffold

Reference React Native + Expo scaffold that mirrors what the planner
will emit when `codegen.react_native.enabled=true` and a run generates
`projectType=react-native-expo`. Exists to:

1. **Validate the dep set resolves.** `npm install` here proves the Expo /
   React / React Native / navigation version pins are compatible. Run it
   locally before bumping the SDK in
   `prompts/scaffolds/react-native-expo.md`.
2. **Exercise the smoke command.** The scaffold's declared smoke is
   `npx expo export --platform android --output-dir dist && npx tsc
   --noEmit`. If that goes red here, it'll go red in runs too.

## Run it

```bash
cd scripts/rn-smoke
npm install
npm run smoke      # expo export + tsc --noEmit
npm run typecheck  # tsc --noEmit only (fast)
```

The ~500 MB `node_modules/` is git-ignored.

## When to refresh

- New Expo SDK lands and we want to pin the scaffold forward.
- A run fails with "peer dep mismatch" or similar — reproduce it here
  first before touching the scaffold template.
