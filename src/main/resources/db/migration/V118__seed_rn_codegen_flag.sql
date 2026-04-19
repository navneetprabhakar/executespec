-- React Native (Expo) code-generation feature flag seed.
--
-- See docs/plans/android-codegen-plan.md (rev 2, 2026-04-18). This migration
-- is step 1 scaffolding only: seeds the feature-flag row. Subsequent PRs wire
-- the planner accept path, stack detection, shell sandbox verb allowlists,
-- smoke / preview / deploy paths, and the UI project-selector entry behind
-- this flag.
--
-- Default FALSE — flipping to true is the cutover switch once the scaffold
-- + planner + build + deploy slices are all green.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('codegen.react_native.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Enable React Native + Expo code generation (cross-platform Android + iOS from one codebase). When true, the planner accepts projectType=react-native-expo and runtimeSurface=MOBILE_APP, the UI project-selector surfaces the "Mobile (React Native)" option, and the REACT_NATIVE deploy strategy routes through EAS Build. Default FALSE until the scaffold, stack detection, sandbox allowlist, smoke, preview, and deploy slices are all green.')
ON CONFLICT (flag_key) DO NOTHING;
